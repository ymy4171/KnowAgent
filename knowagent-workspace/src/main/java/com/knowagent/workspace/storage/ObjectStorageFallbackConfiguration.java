package com.knowagent.workspace.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the fail-fast {@link ObjectStorageGateway} when object storage is not
 * configured. The condition is the exact complement of
 * {@link MinioObjectStorageConfiguration#objectStorageGateway}: active only when
 * {@code minio.endpoint} is missing or explicitly {@code false}, so exactly one
 * gateway bean exists in any context and existing integration tests keep booting
 * without Docker/MinIO.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "minio", name = "endpoint", havingValue = "false", matchIfMissing = true)
public class ObjectStorageFallbackConfiguration {

    @Bean
    public ObjectStorageGateway objectStorageGateway() {
        return new UnavailableObjectStorageGateway();
    }
}
