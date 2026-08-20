package com.knowagent.api.modelprovider.dto;

import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;

/** Public representation of one enabled provider model. */
public record EnabledModelResponse(String name, ModelCapability capability) {
    public static EnabledModelResponse from(EnabledModel model) {
        return new EnabledModelResponse(model.name(), model.capability());
    }
}
