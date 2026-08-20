package com.knowagent.api.modelprovider.dto;

import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** HTTP representation of one enabled provider model. */
public record EnabledModelRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull ModelCapability capability
) {
    public EnabledModel toDomain() {
        return new EnabledModel(name, capability);
    }
}
