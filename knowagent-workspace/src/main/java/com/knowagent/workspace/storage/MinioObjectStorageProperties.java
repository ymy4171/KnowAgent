package com.knowagent.workspace.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO connectivity bound from the environment ({@code MINIO_ENDPOINT},
 * {@code MINIO_ACCESS_KEY}, {@code MINIO_SECRET_KEY}, {@code MINIO_BUCKET},
 * {@code MINIO_REGION}). The bucket is fixed by configuration and provisioned
 * idempotently at startup; application.yml carries no credentials.
 *
 * <p>Blanks are validated here so a misconfigured deployment fails fast at startup
 * instead of surfacing as an opaque SDK error on the first upload.
 */
@ConfigurationProperties(prefix = "minio")
public record MinioObjectStorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region) {

    public static final String DEFAULT_BUCKET = "knowledge";

    public MinioObjectStorageProperties {
        if (isBlank(endpoint)) {
            throw new IllegalArgumentException("minio.endpoint must be configured when MinIO is enabled");
        }
        if (isBlank(accessKey) || isBlank(secretKey)) {
            throw new IllegalArgumentException("minio.access-key and minio.secret-key must be configured when MinIO is enabled");
        }
        bucket = isBlank(bucket) ? DEFAULT_BUCKET : bucket;
        region = isBlank(region) ? null : region;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Credentials must never appear in configuration dumps or startup diagnostics. */
    @Override
    public String toString() {
        return "MinioObjectStorageProperties[endpoint=" + endpoint
                + ", accessKey=[REDACTED], secretKey=[REDACTED], bucket=" + bucket
                + ", region=" + region + "]";
    }
}
