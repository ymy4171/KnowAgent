package com.knowagent.model.embedding;

import com.knowagent.common.tenant.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Request value object for {@link EmbeddingGateway#embed}. Only plain Java types are
 * used; {@code tenantId} and {@code providerId} must come from a trusted context (the
 * authenticated principal or an async event envelope), never from chunk text or any
 * untrusted input (Rule 3).
 *
 * @param expectedDimensions optional expected vector dimension (for example the Milvus
 *                           collection dimension configured for the knowledge base);
 *                           when present it is sent to the provider and validated
 *                           against every returned vector, otherwise the dimension is
 *                           inferred from the response and checked for consistency
 * @param texts             the text chunks to embed, in the order the caller wants the
 *                          vectors returned
 */
public record EmbeddingRequest(
        TenantId tenantId,
        UUID providerId,
        String model,
        Integer expectedDimensions,
        List<String> texts) {

    public EmbeddingRequest {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(model, "model must not be null");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (expectedDimensions != null && expectedDimensions <= 0) {
            throw new IllegalArgumentException("expectedDimensions must be > 0 when supplied");
        }
        texts = List.copyOf(Objects.requireNonNull(texts, "texts must not be null"));
    }

    @Override
    public String toString() {
        return "EmbeddingRequest[tenantId=" + tenantId + ", providerId=" + providerId
                + ", model=" + model + ", expectedDimensions=" + expectedDimensions
                + ", textCount=" + texts.size() + "]";
    }
}
