package com.knowagent.knowledge.document;

import java.io.InputStream;
import java.util.Objects;

/**
 * The input to a document parser: the controlled content stream supplied by the
 * server-side {@code ObjectStorageGateway} plus the metadata needed to parse and
 * report it. A parser must never download content from an arbitrary URL - it only
 * consumes the stream handed to it here and closes it. {@code objectKey} is used for
 * identity only and must never appear in error messages or logs.
 *
 * @param objectKey the server-side object key of the source document
 * @param fileName  the display/original filename, used only as metadata
 * @param mimeType  the already-detected canonical MIME type (from content, at upload time)
 * @param size      the declared byte size of the stream
 * @param content   the closeable content stream; the parser owns it and must close it
 */
public record ParseSource(
        String objectKey,
        String fileName,
        String mimeType,
        long size,
        InputStream content
) {

    public ParseSource {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }

    @Override
    public String toString() {
        return "ParseSource[objectKey=[REDACTED], fileName=[REDACTED], mimeType="
                + mimeType + ", size=" + size + ", content=[REDACTED]]";
    }
}
