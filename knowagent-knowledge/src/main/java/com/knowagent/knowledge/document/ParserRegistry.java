package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the single local parser for a document by its already-detected canonical MIME
 * type - decided from content at upload time, never from a filename or header. The
 * MIME-to-parser map is built once in the constructor and immutable, so selection is
 * deterministic and safe for concurrent use. Two parsers claiming the same MIME fail the
 * registry at construction (an ambiguous registry must not be deployed), and a MIME with
 * no parser fails {@link #parse} with the stable {@link ErrorCode#UNSUPPORTED_DOCUMENT_TYPE}.
 */
@Service
public class ParserRegistry {

    private final Map<String, DocumentParser> parsersByMime;
    private final List<DocumentParser> parsers;

    public ParserRegistry(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
        Map<String, DocumentParser> byMime = new HashMap<>();
        for (DocumentParser parser : parsers) {
            for (String mime : parser.supportedMimeTypes()) {
                DocumentParser previous = byMime.putIfAbsent(mime, parser);
                if (previous != null && previous != parser) {
                    throw new IllegalStateException(
                            "Multiple parsers declare MIME type '" + mime + "'; the parser registry must be unambiguous.");
                }
            }
        }
        this.parsersByMime = Map.copyOf(byMime);
    }

    /**
     * Parses the source with the unique parser for its detected MIME type, or throws
     * {@link ErrorCode#UNSUPPORTED_DOCUMENT_TYPE} when no parser handles it. The selected
     * parser owns the stream; when no parser exists, the registry closes it before
     * returning the stable error.
     */
    public ParsedDocument parse(ParseSource source) {
        Objects.requireNonNull(source, "source must not be null");
        DocumentParser parser = parsersByMime.get(normalizeMime(source.mimeType()));
        if (parser == null) {
            closeQuietly(source);
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                    "The document type is not supported.");
        }
        return parser.parse(source);
    }

    /** The registered parsers, in injection order. */
    public List<DocumentParser> parsers() {
        return parsers;
    }

    private static String normalizeMime(String mimeType) {
        return mimeType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private static void closeQuietly(ParseSource source) {
        try {
            source.content().close();
        } catch (IOException ignored) {
            // Best effort; the unsupported-type result must remain stable.
        }
    }
}
