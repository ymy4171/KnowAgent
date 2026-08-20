package com.knowagent.model.infrastructure.embedding.config;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.crypto.AesGcmSecretCipher;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.infrastructure.embedding.OpenAiCompatibleEmbeddingGateway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link EmbeddingGatewayConfiguration} assembles exactly one
 * {@link EmbeddingGateway} bean even with no {@code MeterRegistry} available, so the
 * API and worker applications can never fail to start from competing provider beans.
 */
class EmbeddingGatewayContextTest {

    @Test
    void contextRegistersASingleEmbeddingGatewayBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(EmbeddingGatewayConfiguration.class, Dependencies.class);
            context.refresh();

            var gateways = context.getBeansOfType(EmbeddingGateway.class);
            assertThat(gateways).hasSize(1);
            assertThat(gateways.values()).singleElement().isInstanceOf(OpenAiCompatibleEmbeddingGateway.class);

            // No MeterRegistry bean present: the gateway must still start (no-op metrics).
            assertThat(context.getBeansOfType(io.micrometer.core.instrument.MeterRegistry.class)).isEmpty();
        }
    }

    /** Minimal collaborators for the configuration; real wiring comes from the apps. */
    @Configuration
    static class Dependencies {

        @Bean
        ModelProviderRepository modelProviderRepository() {
            return new FakeRepository();
        }

        @Bean
        SecretCipher secretCipher() {
            return new AesGcmSecretCipher(
                    Map.of(1, new SecretKeySpec(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES")), 1);
        }
    }

    /** Minimal in-memory repository so the configuration can resolve its dependency. */
    static final class FakeRepository implements ModelProviderRepository {
        @Override
        public void save(com.knowagent.model.provider.ModelProvider provider) {
        }

        @Override
        public java.util.Optional<com.knowagent.model.provider.ModelProvider> findById(TenantId tenantId,
                                                                                        java.util.UUID id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.knowagent.model.provider.ModelProvider> findByIdForUpdate(TenantId tenantId,
                                                                                                java.util.UUID id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.knowagent.model.provider.ModelProvider> findByIdForKeyShare(TenantId tenantId,
                                                                                                  java.util.UUID id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.knowagent.model.provider.ModelProvider> findActiveByKey(TenantId tenantId,
                                                                                              String providerKey) {
            return java.util.Optional.empty();
        }

        @Override
        public com.knowagent.model.provider.ModelProviderPage page(TenantId tenantId, int page, int size) {
            return new com.knowagent.model.provider.ModelProviderPage(java.util.List.of(), 0);
        }

        @Override
        public int updateConfig(com.knowagent.model.provider.ModelProvider provider) {
            return 0;
        }

        @Override
        public int softDelete(TenantId tenantId, java.util.UUID id, long version) {
            return 0;
        }
    }
}
