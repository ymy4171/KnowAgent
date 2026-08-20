package com.knowagent.workspace.storage;

import com.knowagent.common.tenant.TenantId;

import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Uploads an object under a caller-supplied {@link ObjectKey}. The tenant id is part
 * of the command so the adapter can re-verify the key prefix; the client never
 * addresses a bucket or physical path.
 *
 * <p>{@code sha256} is optional and, when present, is stored as object user metadata so
 * {@link ObjectStorageGateway#stat} can return it. It is computed by the caller (the
 * upload pipeline) before the stream is handed over - never from raw file content held
 * in this command's {@code toString}.
 */
public record PutObjectCommand(
        TenantId tenantId,
        ObjectKey key,
        String contentType,
        long size,
        InputStream content,
        String sha256
) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public PutObjectCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (sha256 != null && !SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
    }

    public PutObjectCommand(TenantId tenantId, ObjectKey key, String contentType, long size, InputStream content) {
        this(tenantId, key, contentType, size, content, null);
    }
}
