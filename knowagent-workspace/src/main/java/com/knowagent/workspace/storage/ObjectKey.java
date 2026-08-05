package com.knowagent.workspace.storage;

import java.util.Objects;

public record ObjectKey(String value) {

    public ObjectKey {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}

