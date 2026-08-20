package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.file.DocumentType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@code text/plain} and {@code text/markdown} content. The text is decoded
 * from the content bytes (UTF-8 with BOM handling, UTF-16 BOMs, and a lenient
 * single-byte fallback so no supported encoding can fail) and normalized to {@code \n}.
 * Plain text becomes a single section; Markdown is split on {@code #} headings into
 * sections carrying a numbered heading path such as {@code "1.1"}.
 */
@Service
public class TxtMarkdownParser implements DocumentParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Set<String> MIME_TYPES = Set.of("text/plain", "text/markdown");

    private final ParseProperties properties;

    public TxtMarkdownParser(ParseProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Set<String> supportedMimeTypes() {
        return MIME_TYPES;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        ParseBudget budget = new ParseBudget(properties);
        try (SourceSpool.SpooledFile spooled = SourceSpool.spool(source, properties.maxBytes())) {
            budget.checkTime();
            String text;
            try {
                text = decode(spooled.path());
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.CORRUPT_DOCUMENT,
                        "The document could not be read.");
            }
            if (text.isEmpty()) {
                throw new BusinessException(ErrorCode.EMPTY_DOCUMENT, "The document contains no text.");
            }
            boolean markdown = DocumentType.fromCanonicalMime(source.mimeType())
                    .map(DocumentType.TEXT_MARKDOWN::equals).orElse(false);
            SectionBuilder builder = new SectionBuilder(properties.maxCharacters(), budget);
            for (String line : text.split("\\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                if (markdown) {
                    Matcher heading = HEADING.matcher(line);
                    if (heading.matches()) {
                        builder.appendLine(line, heading.group(1).length(), heading.group(2).trim());
                        continue;
                    }
                }
                builder.appendLine(line, null, null);
            }
            if (builder.textLength() == 0) {
                throw new BusinessException(ErrorCode.EMPTY_DOCUMENT, "The document contains no text.");
            }
            budget.checkTime();
            return builder.finish(null, 0);
        }
    }

    /**
     * Decodes the spooled bytes as text and normalizes line endings. BOMs are honored,
     * otherwise strict UTF-8 is attempted first; if that fails (a single-byte encoding
     * like Latin-1), the bytes are decoded leniently so parsing can never fail on
     * encoding. Full charset auto-detection is out of scope for the local parsers.
     */
    private static String decode(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            return normalize(text);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return normalize(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE));
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return normalize(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE));
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            return normalize(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString());
        } catch (CharacterCodingException malformedUtf8) {
            return normalize(new String(bytes, StandardCharsets.ISO_8859_1));
        }
    }

    private static boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
