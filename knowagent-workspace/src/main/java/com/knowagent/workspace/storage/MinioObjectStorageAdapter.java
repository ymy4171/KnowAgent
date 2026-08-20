package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ObjectStorageGateway} backed by the MinIO Java SDK.
 *
 * <p>Every operation first re-verifies that the requested key lives under this
 * tenant's {@code tenants/{tenantId}/} prefix ({@link StorageKeys#isOwnedBy}) so the
 * storage boundary itself refuses cross-tenant addressing. SDK exceptions are mapped
 * to the stable {@link ObjectStorageException} contract; the SDK's default OkHttp
 * connect/read/write timeouts bound every call. No message ever carries the endpoint,
 * bucket, credentials or the object content.
 */
public class MinioObjectStorageAdapter implements ObjectStorageGateway {

    /** Lower-case user-metadata key MinIO stores the upload SHA-256 under. */
    private static final String SHA256_METADATA_KEY = "x-amz-meta-sha256";

    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorageAdapter(MinioClient client, String bucket) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
    }

    @Override
    public StoredObject put(PutObjectCommand command) {
        TenantId tenantId = command.tenantId();
        ObjectKey key = requireOwnedKey(tenantId, command.key());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream content = new DigestInputStream(command.content(), digest);
            PutObjectArgs.Builder args = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key.value())
                    .stream(content, command.size(), -1)
                    .contentType(command.contentType());
            if (command.sha256() != null) {
                args.userMetadata(Map.of(SHA256_METADATA_KEY, command.sha256()));
            }
            client.putObject(args.build());
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return new StoredObject(tenantId, key, command.contentType(), command.size(), sha256);
        } catch (NoSuchAlgorithmException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.UNAVAILABLE,
                    "The storage adapter could not create a SHA-256 digest.", exception);
        } catch (Exception exception) {
            throw mapException(exception);
        }
    }

    @Override
    public StoredObject stat(GetObjectCommand command) {
        TenantId tenantId = command.tenantId();
        ObjectKey key = requireOwnedKey(tenantId, command.key());
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key.value())
                    .build());
            return new StoredObject(tenantId, key,
                    response.contentType(),
                    response.size(),
                    response.headers().get(SHA256_METADATA_KEY) == null
                            ? "" : response.headers().get(SHA256_METADATA_KEY));
        } catch (Exception exception) {
            throw mapException(exception);
        }
    }

    @Override
    public InputStream get(GetObjectCommand command) {
        TenantId tenantId = command.tenantId();
        ObjectKey key = requireOwnedKey(tenantId, command.key());
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key.value())
                    .build());
        } catch (Exception exception) {
            throw mapException(exception);
        }
    }

    @Override
    public void delete(DeleteObjectCommand command) {
        TenantId tenantId = command.tenantId();
        ObjectKey key = requireOwnedKey(tenantId, command.key());
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key.value())
                    .build());
        } catch (ErrorResponseException exception) {
            if (isNotFound(exception)) {
                return; // deleting a missing object is an idempotent success
            }
            throw mapException(exception);
        } catch (Exception exception) {
            throw mapException(exception);
        }
    }

    private static ObjectKey requireOwnedKey(TenantId tenantId, ObjectKey key) {
        if (!StorageKeys.isOwnedBy(tenantId, key)) {
            throw new ObjectStorageException(ObjectStorageException.Reason.INVALID_OPERATION,
                    "The object key is outside the caller's tenant prefix.");
        }
        return key;
    }

    private static boolean isNotFound(ErrorResponseException exception) {
        String code = exception.errorResponse().code();
        return code != null && ("NoSuchKey".equals(code) || "NoSuchObject".equals(code));
    }

    private static ObjectStorageException mapException(Exception exception) {
        if (exception instanceof ErrorResponseException error) {
            String code = error.errorResponse().code();
            if (isNotFound(error)) {
                return new ObjectStorageException(ObjectStorageException.Reason.OBJECT_NOT_FOUND,
                        "The requested object does not exist.", error);
            }
            if (code != null && (code.startsWith("AccessDenied") || code.startsWith("InvalidAccessKeyId")
                    || code.startsWith("SignatureDoesNotMatch") || code.startsWith("InvalidToken"))) {
                return new ObjectStorageException(ObjectStorageException.Reason.ACCESS_DENIED,
                        "The storage service rejected the configured credentials.", error);
            }
            return new ObjectStorageException(ObjectStorageException.Reason.UNAVAILABLE,
                    "The storage service rejected the operation.", error);
        }
        if (exception instanceof ObjectStorageException stored) {
            return stored;
        }
        if (exception instanceof IOException || exception instanceof MinioException) {
            return new ObjectStorageException(ObjectStorageException.Reason.UNAVAILABLE,
                    "The storage service is unavailable.", exception);
        }
        return new ObjectStorageException(ObjectStorageException.Reason.UNAVAILABLE,
                "The storage operation failed.", exception);
    }

    /** Computes a SHA-256 digest while the stream is consumed. */
    private static final class DigestInputStream extends java.io.FilterInputStream {
        private final MessageDigest digest;

        DigestInputStream(InputStream in, MessageDigest digest) {
            super(in);
            this.digest = digest;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                digest.update((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                digest.update(bytes, offset, count);
            }
            return count;
        }
    }
}
