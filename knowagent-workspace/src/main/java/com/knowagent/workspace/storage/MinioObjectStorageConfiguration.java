package com.knowagent.workspace.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MinIO object-storage adapter when {@code minio.endpoint} is configured
 * (bound from the {@code MINIO_ENDPOINT} environment variable).
 *
 * <p>Without the property no MinIO bean is created, so the API and worker start fine
 * in environments that do not use object storage and every existing integration test
 * keeps booting without Docker. When enabled, the configured bucket is provisioned
 * idempotently at startup and a misconfiguration fails fast.
 *
 * <p>Call timeouts are the SDK's default OkHttp connect/read/write bounds (10s each);
 * the MinIO Java SDK does not expose a client-builder override for them. All beans are
 * singletons and {@link MinioClient} is thread-safe.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioObjectStorageProperties.class)
@ConditionalOnProperty(prefix = "minio", name = "endpoint")
public class MinioObjectStorageConfiguration {

    @Bean
    public MinioClient minioClient(MinioObjectStorageProperties properties) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey());
        if (properties.region() != null) {
            builder.region(properties.region());
        }
        return builder.build();
    }

    @Bean
    public ObjectStorageGateway objectStorageGateway(MinioClient minioClient,
                                                     MinioObjectStorageProperties properties) {
        ensureBucket(minioClient, properties.bucket());
        return new MinioObjectStorageAdapter(minioClient, properties.bucket());
    }

    private static void ensureBucket(MinioClient client, String bucket) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "MinIO bucket '" + bucket + "' could not be provisioned at startup", exception);
        }
    }
}
