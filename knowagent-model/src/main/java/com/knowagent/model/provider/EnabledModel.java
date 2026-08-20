package com.knowagent.model.provider;

import java.util.Objects;

/**
 * One entry of the {@code enabled_models} catalog: a concrete model name plus the
 * capability it serves. The name is an opaque provider-side identifier (it is not
 * validated against any provider registry, since none is wired in this milestone).
 */
public record EnabledModel(String name, ModelCapability capability) {

    private static final int MAX_NAME_LENGTH = 255;

    public EnabledModel {
        Objects.requireNonNull(capability, "capability must not be null");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("model name must not be blank");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("model name must contain at most " + MAX_NAME_LENGTH + " characters");
        }
    }
}
