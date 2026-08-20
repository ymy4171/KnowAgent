package com.knowagent.api.modelprovider.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.ModelCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Patch request. A {@code null} field means "keep the current value"; {@code secret} /
 * {@code headers} carry transient plaintext when a new value is submitted, and
 * {@code clearSecret} / {@code clearHeaders} explicitly clear them. An empty string is
 * never a clear-sentinel. {@link #toString()} never renders the plaintext.
 */
public record UpdateModelProviderRequest(
        @Size(max = 99) String providerKey,
        @Size(max = 128) String displayName,
        AdapterType adapterType,
        @Size(max = 1024) String baseUrl,
        @Size(max = 1024) String embeddingBaseUrl,
        @Size(max = 1024) String rerankBaseUrl,
        @Size(max = 3)
        Set<ModelCapability> capabilities,
        @Size(max = 512) List<@Valid EnabledModelRequest> enabledModels,
        JsonNode publicConfig,
        Boolean enabled,
        @Size(max = 16384) String secret,
        @Size(max = 64) Map<String, String> headers,
        boolean clearSecret,
        boolean clearHeaders
) {

    public UpdateModelProviderRequest {
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
        return "UpdateModelProviderRequest[providerKey=" + providerKey + ", adapterType=" + adapterType
                + ", enabled=" + enabled + ", clearSecret=" + clearSecret + ", clearHeaders=" + clearHeaders + "]";
    }
}
