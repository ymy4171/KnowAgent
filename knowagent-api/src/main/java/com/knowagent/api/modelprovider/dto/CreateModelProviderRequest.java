package com.knowagent.api.modelprovider.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.ModelCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create request. {@code secret} and {@code headers} carry transient plaintext that is
 * encrypted immediately by the service; {@link #toString()} never renders them.
 */
public record CreateModelProviderRequest(
        @NotBlank @Size(max = 99) String providerKey,
        @NotBlank @Size(max = 128) String displayName,
        AdapterType adapterType,
        @NotBlank @Size(max = 1024) String baseUrl,
        @Size(max = 1024) String embeddingBaseUrl,
        @Size(max = 1024) String rerankBaseUrl,
        @Size(max = 3)
        Set<ModelCapability> capabilities,
        @Size(max = 512) List<@Valid EnabledModelRequest> enabledModels,
        JsonNode publicConfig,
        Boolean enabled,
        @Size(max = 16384) String secret,
        @Size(max = 64) Map<String, String> headers
) {

    public CreateModelProviderRequest {
        adapterType = adapterType == null ? AdapterType.OPENAI_COMPATIBLE : adapterType;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        enabledModels = enabledModels == null ? List.of() : List.copyOf(enabledModels);
        publicConfig = publicConfig == null ? JsonNodeFactory.instance.objectNode() : publicConfig;
        enabled = enabled == null ? Boolean.TRUE : enabled;
        if (headers != null) {
            headers = Map.copyOf(headers);
        }
    }

    @Override
    public String toString() {
        return "CreateModelProviderRequest[providerKey=" + providerKey + ", adapterType=" + adapterType
                + ", enabled=" + enabled + "]";
    }
}
