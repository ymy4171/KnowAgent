package com.knowagent.knowledge.chunk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChunkDraft invariants: stable chunk index, hash of the exact content, non-negative token
 * count, ordered and content-length-consistent char offsets, 1-based pages, and defensive
 * copies of the section path and metadata so later mutation cannot corrupt a draft.
 */
class ChunkDraftTest {

    private static final String CONTENT = "Alpha body text.";
    private static final String HASH = DeterministicChunker.sha256Hex(CONTENT);

    @Test
    void acceptsAValidDraft() {
        ChunkDraft draft = new ChunkDraft(3, CONTENT, HASH, 7, 10, 10 + CONTENT.length(),
                5, 12, 2, List.of("1", "1.1"), Map.of("token_estimator", "char-run-v1"));

        assertThat(draft.chunkIndex()).isEqualTo(3);
        assertThat(draft.content()).isEqualTo(CONTENT);
        assertThat(draft.contentHash()).isEqualTo(HASH);
        assertThat(draft.startCharOffset()).isEqualTo(10);
        assertThat(draft.endCharOffset()).isEqualTo(10 + CONTENT.length());
        assertThat(draft.sectionPath()).containsExactly("1", "1.1");
    }

    @Test
    void rejectsNegativeChunkIndex() {
        assertThatThrownBy(() -> new ChunkDraft(-1, CONTENT, HASH, 1, 0, CONTENT.length(),
                0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> new ChunkDraft(0, "", HASH, 1, 0, 0, 0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHashThatIsNotSixtyFourLowercaseHex() {
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, "not-a-hash", 1, 0, CONTENT.length(),
                0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, "A".repeat(64), 1, 0, CONTENT.length(),
                0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWellFormedHashThatDoesNotMatchContent() {
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, "0".repeat(64), 1,
                0, CONTENT.length(), 0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match content");
    }

    @Test
    void rejectsOffsetRangeThatMismatchesContentLength() {
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, HASH, 1, 0, CONTENT.length() + 1,
                0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeTokenCountAndUnorderedTokenOffsets() {
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, HASH, -1, 0, CONTENT.length(),
                0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, HASH, 1, 0, CONTENT.length(),
                3, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTokenCountThatDoesNotMatchTokenOffsets() {
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, HASH, 2,
                0, CONTENT.length(), 0, 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal tokenCount");
    }

    @Test
    void rejectsPageLessThanOne() {
        assertThatThrownBy(() -> new ChunkDraft(0, CONTENT, HASH, 1, 0, CONTENT.length(),
                0, 1, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesSectionPathAndMetadataDefensively() {
        List<String> path = new ArrayList<>(List.of("1"));
        Map<String, String> metadata = new HashMap<>(Map.of("token_estimator", "char-run-v1"));
        ChunkDraft draft = new ChunkDraft(0, CONTENT, HASH, 1, 0, CONTENT.length(),
                0, 1, null, path, metadata);

        path.add("mutated");
        metadata.put("mutated", "yes");

        assertThat(draft.sectionPath()).containsExactly("1");
        assertThat(draft.metadata()).containsExactlyEntriesOf(Map.of("token_estimator", "char-run-v1"));
    }
}
