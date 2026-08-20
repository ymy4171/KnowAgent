package com.knowagent.api.modelprovider.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider view exposed by the API. Carries only {@code hasSecret} plus the public
 * configuration - never ciphertext, the decrypted secret, the key version or the
 * internal headers.
 */
public record ModelProviderResponse(
        UUID id,
        String providerKey,
        String displayName,
        AdapterType adapterType,
        String baseUrl,
        String embeddingBaseUrl,
        String rerankBaseUrl,
        boolean hasSecret,
        Set<ModelCapability> capabilities,
        List<EnabledModelResponse> enabledModels,
        JsonNode publicConfig,
        boolean enabled,
        HealthStatus healthStatus,
        long configVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public static ModelProviderResponse from(ModelProvider provider) {
        return new ModelProviderResponse(
                provider.id(),
                provider.providerKey(),
                provider.displayName(),
                provider.adapterType(),
                provider.baseUrl(),
                provider.embeddingBaseUrl(),
                provider.rerankBaseUrl(),
                provider.hasSecret(),
                provider.capabilities(),
                provider.enabledModels().stream().map(EnabledModelResponse::from).toList(),
                provider.publicConfig(),
                provider.enabled(),
                provider.healthStatus(),
                provider.configVersion(),
                provider.createdAt(),
                provider.updatedAt());
    }
}
