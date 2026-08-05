package com.knowagent.knowledge.document;

import java.util.Objects;

public record ParseSource(
        String objectKey,
        String fileName,
        String mimeType,
        long size
) {

    public ParseSource {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}

