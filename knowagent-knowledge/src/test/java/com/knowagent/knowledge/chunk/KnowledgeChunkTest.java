package com.knowagent.knowledge.chunk;

import com.knowagent.common.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KnowledgeChunk invariants mirror the {@code knowledge_chunks} constraints: the hash
 * format, paired and ordered offsets, 1-based pages, immutable copies, and a toString that
 * never exposes the content.
 */
class KnowledgeChunkTest {

    private static final UUID ID = UUID.randomUUID();
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID KB = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();
    private static final String CONTENT = "Chunk body text.";
    private static final String HASH = DeterministicChunker.sha256Hex(CONTENT);
    private static final Instant NOW = Instant.now();

    @Test
    void acceptsAValidChunk() {
        KnowledgeChunk chunk = chunk(CONTENT, HASH, 5L, 5L + CONTENT.length(), 2L, 7L);
        assertThat(chunk.chunkIndex()).isEqualTo(0);
        assertThat(chunk.indexStatus()).isEqualTo(ChunkIndexStatus.PENDING);
        assertThat(chunk.startCharOffset()).isEqualTo(5L);
        assertThat(chunk.endCharOffset()).isEqualTo(5L + CONTENT.length());
    }

    @Test
    void acceptsNullPairedOffsets() {
        KnowledgeChunk chunk = chunk(CONTENT, HASH, null, null, null, null);
        assertThat(chunk.startCharOffset()).isNull();
        assertThat(chunk.endCharOffset()).isNull();
    }

    @Test
    void rejectsHalfPairedOffsets() {
        assertThatThrownBy(() -> chunk(CONTENT, HASH, 5L, null, 2L, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunk(CONTENT, HASH, 5L, 5L + CONTENT.length(), 2L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReversedOffsets() {
        assertThatThrownBy(() -> chunk(CONTENT, HASH, 9L, 5L, 2L, 7L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyContentAndBadHash() {
        assertThatThrownBy(() -> chunk("", HASH, 0L, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunk(CONTENT, "broken", 0L, (long) CONTENT.length(), 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeTokenCountAndPageBelowOne() {
        assertThatThrownBy(() -> new KnowledgeChunk(ID, TENANT, KB, FILE, 0, CONTENT, HASH, -1,
                0L, (long) CONTENT.length(), 0L, 1L, null, List.of(), Map.of(),
                ChunkIndexStatus.PENDING, null, null, null, 0L, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeChunk(ID, TENANT, KB, FILE, 0, CONTENT, HASH, 1,
                0L, (long) CONTENT.length(), 0L, 1L, 0, List.of(), Map.of(),
                ChunkIndexStatus.PENDING, null, null, null, 0L, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesListsAndMapsDefensively() {
        List<String> path = new ArrayList<>(List.of("1"));
        Map<String, String> metadata = new HashMap<>(Map.of("token_estimator", "char-run-v1"));
        KnowledgeChunk chunk = new KnowledgeChunk(ID, TENANT, KB, FILE, 0, CONTENT, HASH, 4,
                0L, (long) CONTENT.length(), 0L, 4L, null, path, metadata,
                ChunkIndexStatus.PENDING, null, null, null, 0L, NOW, NOW);

        path.add("mutated");
        metadata.put("mutated", "yes");

        assertThat(chunk.sectionPath()).containsExactly("1");
        assertThat(chunk.metadata()).containsExactlyEntriesOf(Map.of("token_estimator", "char-run-v1"));
    }

    @Test
    void toStringNeverExposesContent() {
        KnowledgeChunk chunk = chunk(CONTENT, HASH, 0L, (long) CONTENT.length(), 0L, 4L);
        assertThat(chunk.toString()).doesNotContain(CONTENT);
        assertThat(chunk.toString()).contains("chunkIndex=0", "PENDING");
    }

    private static KnowledgeChunk chunk(String content, String hash,
                                        Long startChar, Long endChar, Long startToken, Long endToken) {
        return new KnowledgeChunk(ID, TENANT, KB, FILE, 0, content, hash, 4,
                startChar, endChar, startToken, endToken, null, List.of(), Map.of(),
                ChunkIndexStatus.PENDING, null, null, null, 0L, NOW, NOW);
    }
}
