package com.knowagent.model.infrastructure.embedding;

import com.knowagent.common.tenant.TenantId;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Bounded, access-ordered cache of ready-to-use model clients. The key is
 * {@code (tenantId, providerId, configVersion)}, so a provider configuration update
 * (which bumps {@code configVersion}) never reuses a stale client, and a tenant can
 * never resolve a key that names another tenant's provider.
 *
 * <p>The cache holds only the key and the built client - never a decrypted secret or
 * header (Rule 10). Decryption happens once inside the {@code factory} at the HTTP-call
 * boundary, and the plaintext lives only inside the client's request headers until the
 * entry is evicted (bounded by {@code maxSize}, least-recently-accessed first).
 */
final class EmbeddingModelClientCache {

    private final int maxSize;
    private final Map<EmbeddingClientKey, OpenAiEmbeddingModel> clients;

    EmbeddingModelClientCache(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        this.maxSize = maxSize;
        // Access-ordered so eviction always drops the least-recently-used client.
        this.clients = new LinkedHashMap<>(16, 0.75f, true);
    }

    synchronized OpenAiEmbeddingModel getOrCreate(
            EmbeddingClientKey key,
            Function<EmbeddingClientKey, OpenAiEmbeddingModel> factory) {
        OpenAiEmbeddingModel cached = clients.get(key);
        if (cached != null) {
            return cached;
        }
        OpenAiEmbeddingModel built = factory.apply(key);
        clients.put(key, built);
        if (clients.size() > maxSize) {
            Iterator<OpenAiEmbeddingModel> iterator = clients.values().iterator();
            iterator.next();
            iterator.remove();
        }
        return built;
    }

    synchronized int size() {
        return clients.size();
    }

    /** Cache key: tenant id, provider id and the provider's configuration version. */
    record EmbeddingClientKey(TenantId tenantId, UUID providerId, long configVersion) {
    }
}
