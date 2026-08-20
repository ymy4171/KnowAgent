package com.knowagent.knowledge.file;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The document types an upload may be. Each value carries the canonical MIME type that
 * is stored on the file row and handed to object storage; the canonical MIME is decided
 * by {@link com.knowagent.knowledge.application.service.DocumentTypeDetector} from the
 * file <em>content</em> (Tika sniffing), never from the client filename or
 * {@code Content-Type} header.
 */
public enum DocumentType {

    TEXT_PLAIN("text/plain"),
    TEXT_MARKDOWN("text/markdown"),
    PDF("application/pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final String canonicalMime;

    DocumentType(String canonicalMime) {
        this.canonicalMime = canonicalMime;
    }

    public String canonicalMime() {
        return canonicalMime;
    }

    /**
     * Resolves a detected MIME type (case-insensitive, ignoring any parameters after
     * {@code ;}) to a supported {@link DocumentType}, or empty for unknown/unsupported
     * content.
     */
    public static Optional<DocumentType> fromCanonicalMime(String detectedMime) {
        if (detectedMime == null) {
            return Optional.empty();
        }
        String base = detectedMime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return Arrays.stream(values())
                .filter(type -> type.canonicalMime.equals(base))
                .findFirst();
    }
}
