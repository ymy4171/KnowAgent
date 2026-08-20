package com.knowagent.knowledge.document;

import java.io.ByteArrayInputStream;

/** Convenience {@link ParseSource} builders for parser tests. */
final class TestSources {

    private TestSources() {
    }

    static ParseSource of(String mime, byte[] content) {
        return new ParseSource("object-key/for/test", "sample.bin", mime, content.length,
                new ByteArrayInputStream(content));
    }

    static ParseSource tracked(String mime, CloseTrackingInputStream content) throws java.io.IOException {
        return new ParseSource("object-key/for/test", "sample.bin", mime, content.available(),
                content);
    }
}
