package com.knowagent.knowledge.infrastructure.vector;

import com.knowagent.knowledge.vector.VectorStoreGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the fail-fast {@link VectorStoreGateway} when the vector store is not
 * configured. The condition is the exact complement of
 * {@link VectorStoreConfiguration#vectorStoreGateway}: active only when
 * {@code knowagent.vector.milvus.uri} is missing or explicitly {@code false}, so
 * exactly one gateway bean exists in any context and existing integration tests
 * keep booting without Docker/Milvus.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "knowagent.vector.milvus", name = "uri", havingValue = "false", matchIfMissing = true)
public class VectorStoreFallbackConfiguration {

    @Bean
    public VectorStoreGateway vectorStoreGateway() {
        return new UnavailableVectorStoreGateway();
    }
}
