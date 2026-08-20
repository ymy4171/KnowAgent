package com.knowagent.knowledge.chunk;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One chunk produced by the chunker, before it is persisted. Everything the persistence
 * layer needs is computed here deterministically: a stable {@code chunkIndex}, the
 * {@code contentHash} of the exact content, the estimated token count, character and
 * token offsets into the document text, and the reference metadata propagated from the
 * covering {@code ParsedSection} (page number and heading path) plus the token-estimator
 * algorithm version.
 *
 * @param chunkIndex       stable 0-based position of the chunk within its file
 * @param content          the chunk text, a slice of the document text
 * @param contentHash      SHA-256 of {@code content}, 64 lowercase hex characters
 * @param tokenCount       estimated token count of {@code content} under the estimator
 * @param startCharOffset  inclusive character offset into the document text
 * @param endCharOffset    exclusive character offset into the document text
 * @param startTokenOffset number of tokens in the document text before the chunk
 * @param endTokenOffset   number of tokens in the document text up to the chunk's end
 * @param pageNumber       page number propagated from the covering section; null when not paginated
 * @param sectionPath      heading path segments from the covering section (e.g. ["1","1.1"]); empty when none
 * @param metadata         additional chunk metadata; always includes the token estimator version
 */
public record ChunkDraft(
        int chunkIndex,
        String content,
        String contentHash,
        int tokenCount,
        long startCharOffset,
        long endCharOffset,
        long startTokenOffset,
        long endTokenOffset,
        Integer pageNumber,
        List<String> sectionPath,
        Map<String, String> metadata
) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public ChunkDraft {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        if (!SHA256_HEX.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be 64 lowercase hex characters");
        }
        if (!contentHash.equals(DeterministicChunker.sha256Hex(content))) {
            throw new IllegalArgumentException("contentHash must match content");
        }
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must not be negative");
        }
        if (startCharOffset < 0 || endCharOffset < startCharOffset) {
            throw new IllegalArgumentException("char offsets must satisfy 0 <= start <= end");
        }
        if (endCharOffset - startCharOffset != content.length()) {
            throw new IllegalArgumentException("char offset range length must equal content length");
        }
        if (startTokenOffset < 0 || endTokenOffset < startTokenOffset) {
            throw new IllegalArgumentException("token offsets must satisfy 0 <= start <= end");
        }
        if (endTokenOffset - startTokenOffset != tokenCount) {
            throw new IllegalArgumentException("token offset range length must equal tokenCount");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be 1-based when present");
        }
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
