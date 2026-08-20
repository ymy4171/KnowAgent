package com.knowagent.model.infrastructure.embedding;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.infrastructure.embedding.EmbeddingModelClientCache.EmbeddingClientKey;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the client cache keys on (tenantId, providerId, configVersion) and bounds
 * its size with least-recently-used eviction.
 */
class EmbeddingModelClientCacheTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID PROVIDER = UUID.randomUUID();

    @Test
    void sameKeyReturnsTheCachedClientAndBuildsOnlyOnce() {
        EmbeddingModelClientCache cache = new EmbeddingModelClientCache(8);
        CountingFactory factory = new CountingFactory();

        EmbeddingClientKey key = new EmbeddingClientKey(TENANT, PROVIDER, 1);
        OpenAiEmbeddingModel first = cache.getOrCreate(key, factory);
        OpenAiEmbeddingModel second = cache.getOrCreate(key, factory);

        assertThat(second).isSameAs(first);
        assertThat(factory.calls).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void configVersionChangeBuildsANewClient() {
        EmbeddingModelClientCache cache = new EmbeddingModelClientCache(8);
        CountingFactory factory = new CountingFactory();

        OpenAiEmbeddingModel v1 = cache.getOrCreate(new EmbeddingClientKey(TENANT, PROVIDER, 1), factory);
        OpenAiEmbeddingModel v2 = cache.getOrCreate(new EmbeddingClientKey(TENANT, PROVIDER, 2), factory);

        assertThat(v2).isNotSameAs(v1);
        assertThat(factory.calls).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void providerIdAndTenantPartitionTheCache() {
        EmbeddingModelClientCache cache = new EmbeddingModelClientCache(8);
        CountingFactory factory = new CountingFactory();
        UUID otherProvider = UUID.randomUUID();
        TenantId otherTenant = TenantId.of(UUID.randomUUID());

        cache.getOrCreate(new EmbeddingClientKey(TENANT, PROVIDER, 1), factory);
        cache.getOrCreate(new EmbeddingClientKey(otherTenant, PROVIDER, 1), factory);
        cache.getOrCreate(new EmbeddingClientKey(TENANT, otherProvider, 1), factory);

        assertThat(factory.calls).isEqualTo(3);
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void evictsTheLeastRecentlyUsedClientWhenOverCapacity() {
        EmbeddingModelClientCache cache = new EmbeddingModelClientCache(2);
        CountingFactory factory = new CountingFactory();
        EmbeddingClientKey a = new EmbeddingClientKey(TENANT, PROVIDER, 1);
        EmbeddingClientKey b = new EmbeddingClientKey(TENANT, PROVIDER, 2);
        EmbeddingClientKey c = new EmbeddingClientKey(TENANT, PROVIDER, 3);

        cache.getOrCreate(a, factory);
        cache.getOrCreate(b, factory);
        cache.getOrCreate(a, factory);      // a becomes most recently used
        cache.getOrCreate(c, factory);      // evicts b (least recently used)

        assertThat(cache.size()).isEqualTo(2);
        assertThat(factory.callsFor(b)).isEqualTo(1);
        assertThat(factory.callsFor(a)).isEqualTo(1);
        assertThat(cache.getOrCreate(b, factory)).isNotNull(); // rebuilt
        assertThat(factory.callsFor(b)).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(2);
    }

    /** Counts factory invocations per key so tests can observe rebuilds. */
    private static final class CountingFactory implements Function<EmbeddingClientKey, OpenAiEmbeddingModel> {

        private final Map<EmbeddingClientKey, Integer> perKey = new ConcurrentHashMap<>();
        int calls;

        @Override
        public OpenAiEmbeddingModel apply(EmbeddingClientKey key) {
            calls++;
            perKey.merge(key, 1, Integer::sum);
            // A real client pointing at an unreachable address; never called here.
            OpenAiApi api = OpenAiApi.builder().baseUrl("http://127.0.0.1:1").apiKey("unused").build();
            return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().build(), RetryTemplate.builder().maxAttempts(1).build());
        }

        int callsFor(EmbeddingClientKey key) {
            return perKey.getOrDefault(key, 0);
        }
    }
}
