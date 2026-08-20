package com.knowagent.model.provider;

/**
 * The concrete protocol an {@link ModelProvider} is adapted to. Values match the
 * uppercase {@code adapter_type} CHECK values in {@code V5__model_providers.sql};
 * only {@code OPENAI_COMPATIBLE} is supported in this milestone.
 */
public enum AdapterType {
    OPENAI_COMPATIBLE
}
