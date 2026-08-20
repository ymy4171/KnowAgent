package com.knowagent.model.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Command to patch a model provider. A {@code null} field means "keep the current
 * value". {@code secret}/{@code headers} carry transient plaintext when a new value is
 * submitted, or are {@code null} to keep the stored value; {@code clearSecret} /
 * {@code clearHeaders} explicitly clear them (an empty string is never a sentinel).
 * {@link #toString()} never renders the plaintext.
 */
public record UpdateModelProviderCommand(
        TenantId tenantId,
        UUID providerId,
        String providerKey,
        String displayName,
        AdapterType adapterType,
        String baseUrl,
        String embeddingBaseUrl,
        String rerankBaseUrl,
        Set<ModelCapability> capabilities,
        List<EnabledModel> enabledModels,
        JsonNode publicConfig,
        Boolean enabled,
        String secret,
        Map<String, String> headers,
        boolean clearSecret,
        boolean clearHeaders,
        UUID actorId
) {

    public UpdateModelProviderCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (capabilities != null) {
            capabilities = Set.copyOf(capabilities);
        }
        if (enabledModels != null) {
            enabledModels = List.copyOf(enabledModels);
        }
        if (headers != null) {
            headers = Map.copyOf(headers);
        }
    }

    @Override
    public String toString() {
        return "UpdateModelProviderCommand[tenantId=" + tenantId + ", providerId=" + providerId
                + ", providerKey=" + providerKey + ", clearSecret=" + clearSecret + ", clearHeaders=" + clearHeaders
                + "]";
    }
}
