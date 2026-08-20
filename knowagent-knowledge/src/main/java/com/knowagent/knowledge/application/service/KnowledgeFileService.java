package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileRepository;
import com.knowagent.knowledge.file.DocumentType;
import com.knowagent.knowledge.file.KnowledgeFile;
import com.knowagent.knowledge.file.KnowledgeFilePage;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.workspace.storage.DeleteObjectCommand;
import com.knowagent.workspace.storage.GetObjectCommand;
import com.knowagent.workspace.storage.ObjectKey;
import com.knowagent.workspace.storage.ObjectStorageException;
import com.knowagent.workspace.storage.ObjectStorageGateway;
import com.knowagent.workspace.storage.PutObjectCommand;
import com.knowagent.workspace.storage.StorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for knowledge-file upload and read.
 *
 * <p>The upload pipeline is bounded and streaming: the multipart stream is spooled to a
 * temp file under {@link #MAX_UPLOAD_BYTES}, hashed with SHA-256 while copying, and the
 * document type is decided from <em>content</em> (Tika sniffing) - never from the
 * filename or a client {@code Content-Type} header. Object storage is written outside
 * the transaction; the file row, the ingest task and the outbox event are then written
 * atomically by {@link KnowledgeFileSubmissionService}. If that transaction fails the
 * freshly uploaded object is compensated away, and a failure to compensate is logged as
 * an alarm - the caller is never told the upload succeeded.
 *
 * <p>Idempotency: an {@code Idempotency-Key} scoped to {@code (tenant, knowledge base)}
 * replays the original file/task when the content hash matches, or is rejected with a
 * conflict when it does not. The tenant id is always supplied by the caller from the
 * authenticated principal.
 */
@Service
public class KnowledgeFileService {

    public static final int MAX_PAGE_SIZE = 100;
    /** Defense-in-depth bound applied while spooling, on top of the multipart resolver limit. */
    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeFileService.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_FILENAME_LENGTH = 512;
    private static final long MAX_SUPPORTED_OFFSET = Integer.MAX_VALUE;

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeFileRepository files;
    private final DocumentTypeDetector documentTypeDetector;
    private final ObjectStorageGateway storage;
    private final KnowledgeFileSubmissionService submission;

    public KnowledgeFileService(KnowledgeBaseRepository knowledgeBases,
                                KnowledgeFileRepository files,
                                DocumentTypeDetector documentTypeDetector,
                                ObjectStorageGateway storage,
                                KnowledgeFileSubmissionService submission) {
        this.knowledgeBases = Objects.requireNonNull(knowledgeBases, "knowledgeBases must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.documentTypeDetector = Objects.requireNonNull(documentTypeDetector, "documentTypeDetector must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.submission = Objects.requireNonNull(submission, "submission must not be null");
    }

    public UploadFileResult upload(UploadFileCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        KnowledgeBase knowledgeBase = knowledgeBases.findById(command.tenantId(), command.knowledgeBaseId())
                .orElseThrow(KnowledgeFileService::notFound);
        if (knowledgeBase.status() != KnowledgeBaseStatus.ACTIVE) {
            throw conflict("The knowledge base is not active; files cannot be uploaded.");
        }
        String idempotencyKey = normalizeIdempotencyKey(command.idempotencyKey());
        String originalFilename = requireFilename(command.originalFilename());

        SpooledContent spooled = spool(command.content());
        try {
            if (spooled.size() == 0) {
                throw validation("The uploaded file is empty.");
            }
            if (idempotencyKey != null) {
                Optional<KnowledgeFile> existing = files.findByUploadIdempotencyKey(
                        command.tenantId(), command.knowledgeBaseId(), idempotencyKey);
                if (existing.isPresent()) {
                    return idempotentOutcome(existing.get(), spooled.sha256());
                }
            }

            DocumentType documentType = documentTypeDetector.detect(spooled.path())
                    .orElseThrow(() -> validation("The uploaded file type is not supported."));
            String fileExtension = deriveExtension(originalFilename);

            UUID fileId = UUID.randomUUID();
            ObjectKey objectKey = StorageKeys.knowledgeFileSource(
                    command.tenantId(), command.knowledgeBaseId(), fileId);
            putObject(command, objectKey, documentType, spooled);

            try {
                KnowledgeFile persisted = submission.submitUpload(new PersistUploadedFileCommand(
                        fileId, command.tenantId(), command.knowledgeBaseId(), idempotencyKey,
                        originalFilename, originalFilename, objectKey.value(), documentType.canonicalMime(),
                        fileExtension, spooled.sha256(), spooled.size(), command.actorId()));
                return new UploadFileResult(persisted, null, false);
            } catch (DuplicateKeyException duplicate) {
                // A concurrent upload with the same idempotency key committed first. Our
                // object is an orphan; remove it, then decide replay vs conflict on the winner.
                compensateDelete(command.tenantId(), command.knowledgeBaseId(), fileId, "concurrent idempotent upload");
                if (idempotencyKey != null) {
                    Optional<KnowledgeFile> existing = files.findByUploadIdempotencyKey(
                            command.tenantId(), command.knowledgeBaseId(), idempotencyKey);
                    if (existing.isPresent()) {
                        return idempotentOutcome(existing.get(), spooled.sha256());
                    }
                }
                throw conflict("The upload conflicted with an existing record; please retry.");
            } catch (RuntimeException failure) {
                compensateDelete(command.tenantId(), command.knowledgeBaseId(), fileId,
                        "database transaction failed after storage");
                throw failure;
            }
        } finally {
            deleteSpooled(spooled.path());
        }
    }

    public KnowledgeFilePage list(TenantId tenantId, UUID knowledgeBaseId,
                                   KnowledgeFileStatus status, int page, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        validatePaging(page, size);
        knowledgeBases.findById(tenantId, knowledgeBaseId).orElseThrow(KnowledgeFileService::notFound);
        return files.page(tenantId, knowledgeBaseId, status, page, size);
    }

    public KnowledgeFile get(TenantId tenantId, UUID knowledgeBaseId, UUID fileId) {
        return files.findById(tenantId, knowledgeBaseId, fileId).orElseThrow(KnowledgeFileService::notFound);
    }

    /** Opens a streaming download over the source object; the caller owns the stream. */
    public FileContent content(TenantId tenantId, UUID knowledgeBaseId, UUID fileId) {
        KnowledgeFile file = get(tenantId, knowledgeBaseId, fileId);
        try {
            InputStream stream = storage.get(new GetObjectCommand(tenantId, new ObjectKey(file.objectKey())));
            return new FileContent(stream, file.contentType(), file.fileSizeBytes(), file.displayName());
        } catch (ObjectStorageException failure) {
            LOG.error("Object storage read failed for knowledge base {} file {}: {}",
                    file.knowledgeBaseId(), fileId, failure.reason(), failure);
            throw storageFailure(failure);
        }
    }

    private void putObject(UploadFileCommand command, ObjectKey objectKey,
                           DocumentType documentType, SpooledContent spooled) {
        try (InputStream in = Files.newInputStream(spooled.path())) {
            storage.put(new PutObjectCommand(command.tenantId(), objectKey,
                    documentType.canonicalMime(), spooled.size(), in, spooled.sha256()));
        } catch (ObjectStorageException failure) {
            LOG.error("Object storage upload failed for knowledge base {} file {}: {}",
                    command.knowledgeBaseId(), objectKey.value(), failure.reason(), failure);
            throw storageFailure(failure);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "The uploaded file could not be read.");
        }
    }

    private void compensateDelete(TenantId tenantId, UUID knowledgeBaseId, UUID fileId, String reason) {
        ObjectKey objectKey = StorageKeys.knowledgeFileSource(tenantId, knowledgeBaseId, fileId);
        try {
            storage.delete(new DeleteObjectCommand(tenantId, objectKey));
        } catch (RuntimeException failure) {
            // Alarmable: the orphaned object must be cleaned up out of band. The caller is
            // still never told the upload succeeded.
            LOG.error("[ALARM] Failed to compensate-remove source object for knowledge base {} file {} ({}); "
                    + "an orphaned object must be cleaned up manually.", knowledgeBaseId, fileId, reason, failure);
        }
    }

    private static UploadFileResult idempotentOutcome(KnowledgeFile existing, String incomingSha256) {
        if (existing.sha256().equals(incomingSha256)) {
            return new UploadFileResult(existing, null, true);
        }
        throw conflict("An upload with this Idempotency-Key but different content already exists.");
    }

    private SpooledContent spool(InputStream content) {
        Path temp;
        try {
            temp = Files.createTempFile("knowagent-upload-", ".part");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "The uploaded file could not be spooled.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream bounded = new LimitedInputStream(content, MAX_UPLOAD_BYTES);
                 OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 DigestOutputStream digestOut = new DigestOutputStream(out, digest)) {
                size = bounded.transferTo(digestOut);
            } catch (TooLarge exceeded) {
                throw validation("The uploaded file exceeds the " + MAX_UPLOAD_BYTES + " byte limit.");
            }
            return new SpooledContent(temp, size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "The uploaded file could not be spooled.");
        } catch (RuntimeException failure) {
            deleteSpooled(temp);
            throw failure;
        }
    }

    private static void deleteSpooled(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort: the temp directory is cleaned by the OS eventually
        }
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw validation("Idempotency-Key must contain at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String requireFilename(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw validation("The uploaded file must have a filename.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_FILENAME_LENGTH) {
            throw validation("The filename must contain at most " + MAX_FILENAME_LENGTH + " characters");
        }
        return trimmed;
    }

    /** Alphanumeric extension derived from the display filename, or null when absent/unsafe. */
    private static String deriveExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.isEmpty() || extension.length() > 32
                || !extension.chars().allMatch(Character::isLetterOrDigit)) {
            return null;
        }
        return extension;
    }

    private static void validatePaging(int page, int size) {
        if (page < 1) {
            throw validation("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) (page - 1) * size;
        if (offset > MAX_SUPPORTED_OFFSET) {
            throw validation("page and size exceed the supported paging range");
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist.");
    }

    private static BusinessException storageFailure(ObjectStorageException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.EXTERNAL_SERVICE_ERROR, "The file could not be stored; please retry.");
        exception.initCause(cause);
        return exception;
    }

    private record SpooledContent(Path path, long size, String sha256) {
    }

    /** Fails as soon as more than {@code limit} bytes have been read, bounding the spool. */
    private static final class TooLarge extends RuntimeException {
    }

    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > limit) {
                throw new TooLarge();
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                this.count += count;
                if (this.count > limit) {
                    throw new TooLarge();
                }
            }
            return count;
        }
    }
}
