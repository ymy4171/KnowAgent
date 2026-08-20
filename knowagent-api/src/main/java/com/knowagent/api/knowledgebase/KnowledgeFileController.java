package com.knowagent.api.knowledgebase;

import com.knowagent.api.knowledgebase.dto.KnowledgeFilePageResponse;
import com.knowagent.api.knowledgebase.dto.KnowledgeFileResponse;
import com.knowagent.api.knowledgebase.dto.UploadFileResponse;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.application.service.FileContent;
import com.knowagent.knowledge.application.service.KnowledgeFileService;
import com.knowagent.knowledge.application.service.UploadFileCommand;
import com.knowagent.knowledge.application.service.UploadFileResult;
import com.knowagent.knowledge.file.KnowledgeFilePage;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge-file endpoints: upload (multipart, accepted asynchronously as 202),
 * listing, detail and streaming content download. The tenant id is always read from the
 * authenticated principal; writes require {@code KNOWLEDGE_FILE_WRITE} and reads
 * {@code KNOWLEDGE_FILE_READ}. Cross-tenant (or cross-knowledge-base) ids surface as a
 * 404. No document parsing, embedding or Milvus work happens here - upload only
 * enqueues.
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/files")
public class KnowledgeFileController {

    private final KnowledgeFileService service;

    public KnowledgeFileController(KnowledgeFileService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_FILE_WRITE')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadFileResponse upload(@AuthenticationPrincipal TenantPrincipal principal,
                                     @PathVariable UUID knowledgeBaseId,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @RequestPart("file") MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            UploadFileResult result = service.upload(new UploadFileCommand(
                    principal.tenantId(), knowledgeBaseId, principal.userId(),
                    idempotencyKey, file.getOriginalFilename(), content));
            return UploadFileResponse.from(result);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The uploaded file could not be read.");
        }
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_FILE_READ')")
    @GetMapping
    public KnowledgeFilePageResponse list(@AuthenticationPrincipal TenantPrincipal principal,
                                          @PathVariable UUID knowledgeBaseId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) KnowledgeFileStatus status) {
        KnowledgeFilePage result = service.list(principal.tenantId(), knowledgeBaseId, status, page, size);
        List<KnowledgeFileResponse> items = result.files().stream()
                .map(KnowledgeFileResponse::from)
                .toList();
        return new KnowledgeFilePageResponse(items, result.total(), page, size);
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_FILE_READ')")
    @GetMapping("/{fileId}")
    public KnowledgeFileResponse get(@AuthenticationPrincipal TenantPrincipal principal,
                                     @PathVariable UUID knowledgeBaseId,
                                     @PathVariable UUID fileId) {
        return KnowledgeFileResponse.from(service.get(principal.tenantId(), knowledgeBaseId, fileId));
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_FILE_READ')")
    @GetMapping("/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@AuthenticationPrincipal TenantPrincipal principal,
                                                         @PathVariable UUID knowledgeBaseId,
                                                         @PathVariable UUID fileId) {
        FileContent fileContent = service.content(principal.tenantId(), knowledgeBaseId, fileId);
        StreamingResponseBody body = outputStream -> {
            try (InputStream in = fileContent.content()) {
                in.transferTo(outputStream);
            }
        };
        String disposition = ContentDisposition.attachment()
                .filename(fileContent.displayName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .contentLength(fileContent.size())
                .contentType(MediaType.parseMediaType(fileContent.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(body);
    }
}
