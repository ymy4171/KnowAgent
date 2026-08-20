package com.knowagent.model.infrastructure.embedding;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.crypto.AesGcmSecretCipher;
import com.knowagent.model.crypto.EncryptedSecret;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.infrastructure.embedding.config.EmbeddingProperties;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import com.knowagent.model.provider.ModelProviderPage;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Shared fixtures for the embedding adapter tests. */
final class EmbeddingTestSupport {

    static final UUID ACTOR = UUID.randomUUID();

    private EmbeddingTestSupport() {
    }

    static SecretCipher cipher() {
        return new AesGcmSecretCipher(
                Map.of(1, new SecretKeySpec(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES")), 1);
    }

    static ModelProvider provider(TenantId tenantId, String baseUrl, String model, long configVersion,
                                  EncryptedSecret secret, EncryptedSecret headers,
                                  Set<ModelCapability> capabilities, List<EnabledModel> enabledModels) {
        return new ModelProvider(UUID.randomUUID(), tenantId, "openai", "OpenAI", AdapterType.OPENAI_COMPATIBLE,
                baseUrl, null, null, secret, headers, capabilities, enabledModels,
                JsonNodeFactory.instance.objectNode(), true, HealthStatus.UNKNOWN, configVersion,
                ACTOR, ACTOR, 0L, Instant.now(), Instant.now(), null);
    }

    static EmbeddingProperties properties(int maxAttempts, Duration readTimeout, Duration totalTimeout) {
        return new EmbeddingProperties(Duration.ofSeconds(5), readTimeout, totalTimeout, maxAttempts,
                Duration.ofMillis(50), 2.0, Duration.ofMillis(200), 2, 8000, 200_000, 16);
    }

    /** In-memory tenant-scoped repository returning one mutable provider. */
    static final class FakeRepository implements ModelProviderRepository {

        private ModelProvider provider;

        void setProvider(ModelProvider provider) {
            this.provider = provider;
        }

        @Override
        public void save(ModelProvider provider) {
            this.provider = provider;
        }

        @Override
        public Optional<ModelProvider> findById(TenantId tenantId, UUID id) {
            return provider != null && provider.tenantId().equals(tenantId) && provider.id().equals(id)
                    ? Optional.of(provider) : Optional.empty();
        }

        @Override
        public Optional<ModelProvider> findByIdForUpdate(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<ModelProvider> findByIdForKeyShare(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<ModelProvider> findActiveByKey(TenantId tenantId, String providerKey) {
            return provider != null && provider.tenantId().equals(tenantId)
                    && provider.providerKey().equals(providerKey) ? Optional.of(provider) : Optional.empty();
        }

        @Override
        public ModelProviderPage page(TenantId tenantId, int page, int size) {
            List<ModelProvider> providers = provider != null && provider.tenantId().equals(tenantId)
                    ? List.of(provider) : List.of();
            return new ModelProviderPage(providers, providers.size());
        }

        @Override
        public int updateConfig(ModelProvider provider) {
            return 0;
        }

        @Override
        public int softDelete(TenantId tenantId, UUID id, long version) {
            return 0;
        }
    }
}
