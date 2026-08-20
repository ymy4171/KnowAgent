package com.knowagent.knowledge.document;

import java.util.List;
import java.util.Objects;

/**
 * The standard, format-independent result of parsing: a canonical full text plus an
 * ordered list of sections that partition it exactly. This is the single contract
 * between parsing and chunking - parsers for TXT/Markdown/PDF/DOCX (or an external OCR
 * service later) all produce it, so no consumer ever sees format-specific types.
 *
 * @param title     the document title from format metadata when available; null otherwise
 * @param text      the full extracted text in reading order
 * @param pageCount the number of pages for paginated formats (PDF); 0 for others
 * @param sections  ordered sections partitioning {@code text} (contiguous, no gaps)
 */
public record ParsedDocument(
        String title,
        String text,
        int pageCount,
        List<ParsedSection> sections
) {

    public ParsedDocument {
        Objects.requireNonNull(text, "text must not be null");
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must not be negative");
        }
        sections = sections == null ? List.of() : List.copyOf(sections);
        long cursor = 0;
        for (ParsedSection section : sections) {
            Objects.requireNonNull(section, "sections must not contain null");
            if (section.startOffset() != cursor || section.endOffset() > text.length()) {
                throw new IllegalArgumentException("sections must be ordered, contiguous and within the text");
            }
            String expected = text.substring(Math.toIntExact(section.startOffset()),
                    Math.toIntExact(section.endOffset()));
            if (!expected.equals(section.content())) {
                throw new IllegalArgumentException("section content must equal its document text slice");
            }
            cursor = section.endOffset();
        }
        if (cursor != text.length()) {
            throw new IllegalArgumentException("sections must cover the whole document text");
        }
    }

    @Override
    public String toString() {
        return "ParsedDocument[title=[REDACTED], text=[REDACTED], pageCount=" + pageCount
                + ", textLength=" + text.length() + ", sectionCount=" + sections.size() + "]";
    }
}
