package com.knowagent.model.embedding;

/**
 * Core port for embedding text with a tenant-configured model provider.
 *
 * <p>Callers (the knowledge module) supply the tenant context and the provider binding
 * in an {@link EmbeddingRequest}. The implementation resolves the provider
 * configuration, batches the input under the configured text / token / request-body
 * limits, calls the provider through a provider-specific adapter and returns the
 * vectors in input order, validated for count, order, finiteness and dimension.
 *
 * <p>The port and its value objects use only plain Java types - no Spring AI or vendor
 * DTO is ever exposed here, so the knowledge module never sees a vendor type. The
 * tenant id in the request must come from a trusted context (the authenticated
 * principal or an async event envelope), never from untrusted input.
 */
public interface EmbeddingGateway {

    /** Embeds {@code request.texts()} and returns one vector per text, in order. */
    EmbeddingResult embed(EmbeddingRequest request);
}

