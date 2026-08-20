package com.knowagent.knowledge.document;

import java.util.Map;
import java.util.Objects;

/**
 * One contiguous slice of the parsed document text, carrying the reference metadata the
 * chunker and citation layer need. Sections of a {@link ParsedDocument} partition its
 * {@code text} exactly: {@code text.substring(startOffset, endOffset)} equals
 * {@code content}, sections are ordered and contiguous, and together they cover the whole
 * text.
 *
 * @param sectionPath the hierarchy path of the section (e.g. {@code "1"}, {@code "1.1"});
 *                    null when the document has no sectioning (plain text, per-page PDF)
 * @param heading     the section heading text (without the {@code #} markers or style
 *                    suffix); null for sections without a heading
 * @param content     the section's own text, a slice of the document text
 * @param pageNumber  1-based page number when the format is paginated (PDF); null otherwise
 * @param startOffset inclusive character offset of {@code content} into the document text
 * @param endOffset   exclusive character offset of {@code content} into the document text
 * @param metadata    additional per-section metadata (never null)
 */
public record ParsedSection(
        String sectionPath,
        String heading,
        String content,
        Integer pageNumber,
        long startOffset,
        long endOffset,
        Map<String, String> metadata
) {

    public ParsedSection {
        Objects.requireNonNull(content, "content must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("offsets must satisfy 0 <= startOffset <= endOffset");
        }
        if (endOffset - startOffset != content.length()) {
            throw new IllegalArgumentException("offset range length must equal content length");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be 1-based when present");
        }
    }

    @Override
    public String toString() {
        return "ParsedSection[sectionPath=" + sectionPath + ", heading=[REDACTED]"
                + ", content=[REDACTED], pageNumber=" + pageNumber
                + ", startOffset=" + startOffset + ", endOffset=" + endOffset
                + ", metadata=[REDACTED]]";
    }
}
