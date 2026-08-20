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
 * Command to create a model provider. {@code secret} and {@code headers} carry
 * plaintext only transiently - the service encrypts them and discards the plaintext -
 * and {@link #toString()} never renders them.
 */
public record CreateModelProviderCommand(
        TenantId tenantId,
        String providerKey,
        String displayName,
        AdapterType adapterType,
        String baseUrl,
        String embeddingBaseUrl,
        String rerankBaseUrl,
        Set<ModelCapability> capabilities,
        List<EnabledModel> enabledModels,
        JsonNode publicConfig,
        boolean enabled,
        String secret,
        Map<String, String> headers,
        UUID actorId
) {

    public CreateModelProviderCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(providerKey, "providerKey must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(adapterType, "adapterType must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(enabledModels, "enabledModels must not be null");
        Objects.requireNonNull(publicConfig, "publicConfig must not be null");
        capabilities = Set.copyOf(capabilities);
        enabledModels = List.copyOf(enabledModels);
        if (headers != null) {
            headers = Map.copyOf(headers);
        }
    }

    @Override
    public String toString() {
        return "CreateModelProviderCommand[tenantId=" + tenantId + ", providerKey=" + providerKey
                + ", adapterType=" + adapterType + ", enabled=" + enabled + "]";
    }
}
