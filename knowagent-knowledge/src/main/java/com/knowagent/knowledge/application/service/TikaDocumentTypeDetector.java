package com.knowagent.knowledge.application.service;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.knowledge.file.DocumentType;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@link DocumentTypeDetector} that decides the type from the <em>content</em>, never
 * from the filename or a client {@code Content-Type} header. Primary sniffing is done by
 * Apache Tika's magic bytes + text heuristics; two trusted-content fallbacks cover the
 * gaps the stock detector leaves:
 *
 * <ul>
 *   <li><b>Markdown</b> - Tika reports markdown text as {@code text/plain}, so a leading
 *       heading or a fenced code block promotes it to {@link DocumentType#TEXT_MARKDOWN}.</li>
 *   <li><b>OOXML DOCX</b> - Tika can report a minimal or unusual DOCX container as a
 *       generic zip, so a zip carrying both {@code [Content_Types].xml} mentioning
 *       {@code wordprocessingml} and a {@code word/document.xml} entry is classified as
 *       {@link DocumentType#DOCX}.</li>
 * </ul>
 *
 * <p>A new detector instance per call keeps the small, shared Tika tables free of
 * shared-state concerns. This is the only place Tika is used in this milestone; parsing
 * the content itself belongs to the ingestion worker.
 */
@Service
public class TikaDocumentTypeDetector implements DocumentTypeDetector {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+");
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("^\\s*```");
    private static final int SNIFF_BYTES = 16 * 1024;

    @Override
    public Optional<DocumentType> detect(Path spooledFile) {
        Objects.requireNonNull(spooledFile, "spooledFile must not be null");
        String sniffed;
        try (InputStream in = Files.newInputStream(spooledFile)) {
            sniffed = new Tika().detect(in);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "The uploaded file could not be inspected.");
        }

        Optional<DocumentType> detected = DocumentType.fromCanonicalMime(sniffed);
        if (detected.isPresent()) {
            if (detected.get() == DocumentType.TEXT_PLAIN && looksLikeMarkdown(spooledFile)) {
                return Optional.of(DocumentType.TEXT_MARKDOWN);
            }
            return detected;
        }
        if (looksLikeOoxmlWord(spooledFile)) {
            return Optional.of(DocumentType.DOCX);
        }
        return Optional.empty();
    }

    private static boolean looksLikeMarkdown(Path spooledFile) {
        try {
            byte[] head = readPrefix(spooledFile);
            String text = new String(head, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                if (MARKDOWN_HEADING.matcher(line).find() || FENCED_CODE_BLOCK.matcher(line).find()) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean looksLikeOoxmlWord(Path spooledFile) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(spooledFile))) {
            boolean hasDocumentXml = false;
            boolean contentTypesMentionWord = false;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("word/document.xml".equals(name)) {
                    hasDocumentXml = true;
                } else if ("[Content_Types].xml".equals(name)) {
                    String contentTypes = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    if (contentTypes.contains("wordprocessingml")) {
                        contentTypesMentionWord = true;
                    }
                }
                if (hasDocumentXml && contentTypesMentionWord) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private static byte[] readPrefix(Path spooledFile) throws IOException {
        byte[] bytes = new byte[SNIFF_BYTES];
        int total = 0;
        try (InputStream in = Files.newInputStream(spooledFile)) {
            int read;
            while (total < bytes.length && (read = in.read(bytes, total, bytes.length - total)) > 0) {
                total += read;
            }
        }
        return total == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, total);
    }
}
