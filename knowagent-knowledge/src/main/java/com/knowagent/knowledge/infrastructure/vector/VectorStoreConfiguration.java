package com.knowagent.knowledge.infrastructure.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.micrometer.core.instrument.MeterRegistry;
import com.knowagent.knowledge.vector.VectorStoreGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Milvus vector store when {@code knowagent.vector.milvus.uri} is
 * configured (bound from the {@code MILVUS_ENDPOINT} environment variable,
 * mirroring the MinIO pattern). Without the property no Milvus bean is created,
 * the API/worker boot fine without Docker/Milvus and the complementary
 * {@link VectorStoreFallbackConfiguration} provides the fail-fast gateway.
 *
 * <p>{@link MilvusCollectionInitializer} runs as a SmartLifecycle so an
 * unreachable Milvus or an incompatible existing collection fails the application
 * context at startup - an existing collection is never dropped.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MilvusVectorProperties.class)
@ConditionalOnProperty(prefix = "knowagent.vector.milvus", name = "uri")
public class VectorStoreConfiguration {

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2(MilvusVectorProperties properties) {
        ConnectConfig.ConnectConfigBuilder<?, ?> builder = ConnectConfig.builder()
                .uri(properties.uri())
                .connectTimeoutMs(properties.connectTimeout().toMillis())
                .rpcDeadlineMs(properties.rpcDeadline().toMillis());
        if (isNotBlank(properties.username())) {
            builder.username(properties.username());
            builder.password(properties.password());
        }
        if (isNotBlank(properties.token())) {
            builder.token(properties.token());
        }
        if (isNotBlank(properties.databaseName())) {
            builder.dbName(properties.databaseName());
        }
        // The SDK connects eagerly in its constructor; a just-started Milvus proxy
        // may reject the first connects, so retry within the init budget.
        return MilvusVectorStoreFactory.connect(builder.build(), properties.initTimeout());
    }

    @Bean
    public VectorMetrics vectorMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new VectorMetrics(meterRegistry.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    public MilvusCallExecutor milvusCallExecutor(VectorMetrics vectorMetrics) {
        return new MilvusCallExecutor(vectorMetrics);
    }

    @Bean
    public VectorStoreGateway vectorStoreGateway(MilvusClientV2 client, MilvusVectorProperties properties,
                                                 MilvusCallExecutor executor, VectorMetrics vectorMetrics) {
        return new MilvusVectorStoreAdapter(new SdkMilvusClientAccess(client), properties, executor, vectorMetrics);
    }

    @Bean
    public MilvusCollectionInitializer milvusCollectionInitializer(MilvusClientV2 client,
                                                                   MilvusVectorProperties properties,
                                                                   MilvusCallExecutor executor) {
        return new MilvusCollectionInitializer(new SdkMilvusClientAccess(client), properties, executor);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
