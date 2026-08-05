package com.knowagent.model.chat;

public record ModelOptions(
        Double temperature,
        Integer maxTokens
) {

    public static ModelOptions defaults() {
        return new ModelOptions(null, null);
    }
}

