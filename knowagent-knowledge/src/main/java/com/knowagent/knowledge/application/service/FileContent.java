package com.knowagent.knowledge.application.service;

import java.io.InputStream;
import java.util.Objects;

/**
 * A streaming download of a knowledge file's source object. The caller owns
 * {@code content} and must close it; the API layer streams it straight to the client.
 * {@code displayName} is the original client filename, shown only as a download
 * attachment name.
 */
public record FileContent(
        InputStream content,
        String contentType,
        long size,
        String displayName
) {

    public FileContent {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
