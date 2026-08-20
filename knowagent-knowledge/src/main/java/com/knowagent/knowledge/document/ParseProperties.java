package com.knowagent.knowledge.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounded-parse configuration (prefix {@code knowagent.parse}). Every parser enforces
 * these limits and rejects oversized, empty or runaway input with a stable error code
 * instead of loading unbounded data into memory. Unset values fall back to the defaults
 * below, so an unconfigured deployment still parses safely.
 *
 * <ul>
 *   <li>{@code maxBytes} - hard cap on the source byte size read from the content stream.</li>
 *   <li>{@code maxPages} - maximum PDF pages accepted.</li>
 *   <li>{@code maxUncompressedBytes} - maximum uncompressed zip entry size (DOCX), the
 *       defense against zip bombs.</li>
 *   <li>{@code maxCharacters} - maximum extracted text characters.</li>
 *   <li>{@code timeout} - cooperative parse timeout checked at page/paragraph boundaries.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "knowagent.parse")
public record ParseProperties(
        long maxBytes,
        int maxPages,
        long maxUncompressedBytes,
        long maxCharacters,
        Duration timeout
) {

    private static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
    private static final int DEFAULT_MAX_PAGES = 1000;
    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;
    private static final long DEFAULT_MAX_CHARACTERS = 10_000_000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public ParseProperties {
        if (maxBytes <= 0) {
            maxBytes = DEFAULT_MAX_BYTES;
        }
        if (maxPages <= 0) {
            maxPages = DEFAULT_MAX_PAGES;
        }
        if (maxUncompressedBytes <= 0) {
            maxUncompressedBytes = DEFAULT_MAX_UNCOMPRESSED_BYTES;
        }
        if (maxCharacters <= 0) {
            maxCharacters = DEFAULT_MAX_CHARACTERS;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
    }

    /** The default bounded-parse configuration, used when nothing is configured. */
    public static ParseProperties defaults() {
        return new ParseProperties(
                DEFAULT_MAX_BYTES,
                DEFAULT_MAX_PAGES,
                DEFAULT_MAX_UNCOMPRESSED_BYTES,
                DEFAULT_MAX_CHARACTERS,
                DEFAULT_TIMEOUT);
    }
}
