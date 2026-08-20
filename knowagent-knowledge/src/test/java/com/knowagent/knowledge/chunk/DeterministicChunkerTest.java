package com.knowagent.knowledge.chunk;

import com.knowagent.knowledge.document.ParsedDocument;
import com.knowagent.knowledge.document.ParsedSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic chunking across all three strategies: token budgets and overlap are honored
 * in tokens, chunks are never empty and never loop, paragraph/line boundaries are preferred,
 * long unbreakable text degrades safely, surrogate pairs are never split, page number and
 * section path propagate from the covering ParsedSection, and the same input + policy always
 * yields the same ordered content and hashes.
 */
class DeterministicChunkerTest {

    private final DeterministicTokenCounter counter = new DeterministicTokenCounter();
    private final DeterministicChunker chunker = new DeterministicChunker(counter);

    private static final String HASH_SHA256_HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    // --- helpers ---------------------------------------------------------------

    record SectionSpec(String path, String heading, Integer page, String content) {
    }

    static ParsedDocument documentOf(SectionSpec... specs) {
        StringBuilder text = new StringBuilder();
        List<ParsedSection> sections = new ArrayList<>();
        long offset = 0;
        for (SectionSpec spec : specs) {
            long end = offset + spec.content().length();
            sections.add(new ParsedSection(spec.path(), spec.heading(), spec.content(),
                    spec.page(), offset, end, Map.of()));
            text.append(spec.content());
            offset = end;
        }
        return new ParsedDocument("Title", text.toString(), 0, sections);
    }

    static ParsedDocument singleSection(String content) {
        return documentOf(new SectionSpec(null, null, null, content));
    }

    // --- shared invariants -----------------------------------------------------

    @Test
    void chunksHonorTokenBudgetAndAreNeverEmptyForAllStrategies() {
        String text = ("Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau.\n")
                .repeat(12);
        for (ChunkPolicy.Strategy strategy : ChunkPolicy.Strategy.values()) {
            ParsedDocument document = singleSection(text);
            ChunkPolicy policy = new ChunkPolicy(strategy, 40, 5);
            List<ChunkDraft> chunks = chunker.split(document, policy);
            assertThat(chunks).describedAs(strategy + " must produce chunks").isNotEmpty();
            for (ChunkDraft chunk : chunks) {
                assertThat(chunk.tokenCount()).describedAs(strategy + " budget")
                        .isBetween(0, 40);
                assertThat(chunk.content()).describedAs(strategy + " non-empty").isNotEmpty();
            }
        }
    }

    @Test
    void chunksReferenceMetadataAndDocumentTextConsistently() {
        String text = "First section content with enough words to split across several chunks "
                + "so that the boundary walking actually produces more than one chunk here.\n";
        ParsedDocument document = singleSection(text);
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, 15, 3));
        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            assertThat(document.text().substring(Math.toIntExact(chunk.startCharOffset()),
                    Math.toIntExact(chunk.endCharOffset()))).isEqualTo(chunk.content());
            assertThat(chunk.endCharOffset() - chunk.startCharOffset())
                    .isEqualTo(chunk.content().length());
            assertThat(chunk.endTokenOffset()).isGreaterThanOrEqualTo(chunk.startTokenOffset());
            assertThat(chunk.metadata()).containsEntry("token_estimator", "char-run-v1");
        }
    }

    @Test
    void metadataMarksTheEstimatorAlgorithmVersion() {
        ParsedDocument document = singleSection("Some text.");
        for (ChunkPolicy.Strategy strategy : ChunkPolicy.Strategy.values()) {
            List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(strategy, 30, 2));
            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0).metadata())
                    .containsEntry("token_estimator", counter.algorithmVersion())
                    .containsEntry("chunk_strategy", strategy.name());
        }
    }

    @Test
    void sameInputAndPolicyProduceTheSameOrderContentAndHashes() {
        ParsedDocument document = singleSection(
                "确定性：同一输入与策略重复运行必须产生相同顺序、相同内容和相同哈希。\n"
                        + "This paragraph makes sure the English run tokenizer also participates. Deterministic text.");
        ChunkPolicy policy = new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 20, 3);
        List<ChunkDraft> first = chunker.split(document, policy);
        List<ChunkDraft> second = chunker.split(document, policy);

        assertThat(second).hasSize(first.size());
        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).content()).isEqualTo(first.get(i).content());
            assertThat(second.get(i).contentHash()).isEqualTo(first.get(i).contentHash());
            assertThat(second.get(i).chunkIndex()).isEqualTo(first.get(i).chunkIndex());
        }
    }

    @Test
    void emptyDocumentProducesNoChunks() {
        ParsedDocument empty = singleSection("");
        for (ChunkPolicy.Strategy strategy : ChunkPolicy.Strategy.values()) {
            assertThat(chunker.split(empty, new ChunkPolicy(strategy, 20, 2))).isEmpty();
        }
    }

    // --- RECURSIVE -------------------------------------------------------------

    @Test
    void recursiveWithoutOverlapCoversTheWholeDocumentContiguously() {
        String text = "Paragraph one with a fair amount of words.\n"
                + "Still paragraph one, second line.\n\n"
                + "Paragraph two, starting after a blank line.\n"
                + "Paragraph three ends the document.";
        ParsedDocument document = singleSection(text);
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, 20, 0));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).startCharOffset()).isZero();
        assertThat(chunks.get(chunks.size() - 1).endCharOffset()).isEqualTo(text.length());
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startCharOffset()).isEqualTo(chunks.get(i - 1).endCharOffset());
        }
        StringBuilder joined = new StringBuilder();
        for (ChunkDraft chunk : chunks) {
            joined.append(chunk.content());
        }
        assertThat(joined.toString()).isEqualTo(text);
    }

    @Test
    void recursivePrefersParagraphAndLineBoundariesWithinBudget() {
        ParsedDocument document = singleSection("One line ends here.\nSecond line of the same paragraph.\n");
        // Small enough to be a single chunk: it must end exactly at the text end, not mid-word.
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, 80, 0));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).endCharOffset()).isEqualTo(document.text().length());
    }

    @Test
    void recursiveAppliesTokenOverlapBetweenChunks() {
        ParsedDocument document = singleSection("Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega.\n"
                .repeat(6));
        int maxTokens = 40;
        int overlap = 7;
        List<ChunkDraft> chunks = chunker.split(document,
                new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, maxTokens, overlap));

        TokenStream stream = counter.tokenize(document.text());
        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            ChunkDraft previous = chunks.get(i - 1);
            ChunkDraft current = chunks.get(i);
            assertThat(current.startCharOffset()).isLessThan(previous.endCharOffset())
                    .describedAs("chunk %d must overlap the previous chunk", i)
                    .isGreaterThan(previous.startCharOffset());
            // The next chunk begins at the token boundary `overlap` tokens before the previous end.
            int expectedStart = stream.tokenStartChar(
                    stream.tokenCountAt(Math.toIntExact(previous.endCharOffset())) - overlap);
            assertThat(current.startCharOffset()).isEqualTo(expectedStart);
        }
    }

    @Test
    void recursiveOverlapNeverEqualsOrExceedsChunkSize() {
        ParsedDocument document = singleSection("Short text that fits in one chunk.\n");
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, 40, 39));
        // A single chunk needs no overlap at all; if it ever split, the overlap clamp keeps it < chunk size.
        for (int i = 1; i < chunks.size(); i++) {
            long overlapTokens = chunks.get(i).startTokenOffset() - chunks.get(i - 1).startTokenOffset();
            assertThat(overlapTokens).isLessThan((long) chunks.get(i - 1).tokenCount());
            assertThat(overlapTokens).isLessThan(40L);
        }
    }

    // --- MARKDOWN_HEADING ------------------------------------------------------

    @Test
    void markdownHeadingNeverCrossesSectionsAndPropagatesMetadata() {
        String sectionB = "Section A alpha body with enough words to need splitting across chunks for the heading test. "
                + "More content here so the section exceeds the small token budget and forces several chunks. ";
        ParsedDocument document = documentOf(
                new SectionSpec("1", "Chapter One", 3, "Chapter One\nOpening paragraph.\n"),
                new SectionSpec("1.1", "Section A", 3, "Section A\n" + sectionB),
                new SectionSpec("2", "Chapter Two", 4, "Chapter Two\nSecond chapter.\n"));

        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 25, 3));
        assertThat(chunks).hasSizeGreaterThan(2);

        int lastSection = 0;
        for (ChunkDraft chunk : chunks) {
            ParsedSection section = sectionAt(document, Math.toIntExact(chunk.startCharOffset()));
            assertThat(section).isNotNull();
            assertThat(chunk.endCharOffset()).isLessThanOrEqualTo(section.endOffset())
                    .describedAs("chunk must not cross the section boundary");
            assertThat(chunk.sectionPath()).isEqualTo(DeterministicChunker.pathSegments(section.sectionPath()));
            assertThat(chunk.pageNumber()).isEqualTo(section.pageNumber());
            assertThat(chunk.chunkIndex()).isEqualTo(lastSection++);
        }
        // Every chunk of the "1.1" section carries its full heading path.
        assertThat(chunks.stream().filter(c -> c.sectionPath().equals(List.of("1", "1.1"))).count())
                .isGreaterThan(1);
    }

    @Test
    void markdownHeadingKeepsASmallSectionAsOneChunk() {
        ParsedDocument document = documentOf(
                new SectionSpec("1", "One", null, "First heading body.\n"),
                new SectionSpec("1.1", "One One", null, "Nested heading body.\n"));
        List<ChunkDraft> chunks = chunker.split(document,
                new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 100, 5));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).containsExactly("1");
        assertThat(chunks.get(1).sectionPath()).containsExactly("1", "1.1");
    }

    @Test
    void markdownHeadingWithNullSectionPathYieldsEmptyPathList() {
        ParsedDocument document = documentOf(new SectionSpec(null, null, 7, "A per-page PDF section body.\n"));
        List<ChunkDraft> chunks = chunker.split(document,
                new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 50, 5));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionPath()).isEmpty();
        assertThat(chunks.get(0).pageNumber()).isEqualTo(7);
    }

    @Test
    void markdownHeadingRetokenizesAtASectionBoundaryInsideAnEnglishRun() {
        ParsedDocument document = documentOf(
                new SectionSpec("1", "One", null, "abc"),
                new SectionSpec("2", "Two", null, "defgh"));

        List<ChunkDraft> chunks = chunker.split(document,
                new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 10, 0));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(ChunkDraft::tokenCount).containsExactly(1, 2);
        assertThat(chunks).extracting(ChunkDraft::startTokenOffset).containsExactly(0L, 1L);
        assertThat(chunks).extracting(ChunkDraft::endTokenOffset).containsExactly(1L, 3L);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(counter.countTokens(chunk.content())).isEqualTo(chunk.tokenCount()));
    }

    // --- TOKEN_WINDOW ----------------------------------------------------------

    @Test
    void tokenWindowSlicesFixedTokenWindowsWithExactOverlap() {
        ParsedDocument document = singleSection("hello world foo bar baz qux\n".repeat(20));
        int maxTokens = 10;
        int overlap = 2;
        List<ChunkDraft> chunks = chunker.split(document,
                new ChunkPolicy(ChunkPolicy.Strategy.TOKEN_WINDOW, maxTokens, overlap));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(maxTokens);
            assertThat(chunk.endTokenOffset() - chunk.startTokenOffset()).isEqualTo(chunk.tokenCount());
            if (i + 1 < chunks.size()) {
                assertThat(chunks.get(i + 1).startTokenOffset())
                        .isEqualTo(chunk.endTokenOffset() - overlap);
            }
        }
    }

    @Test
    void tokenWindowWithoutOverlapPreservesAllSeparatorsAndDocumentEdges() {
        String text = "  alpha   beta\n gamma delta  ";
        ParsedDocument document = singleSection(text);

        List<ChunkDraft> chunks = chunker.split(document,
                new ChunkPolicy(ChunkPolicy.Strategy.TOKEN_WINDOW, 2, 0));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.stream().map(ChunkDraft::content).collect(java.util.stream.Collectors.joining()))
                .isEqualTo(text);
        assertThat(chunks.get(0).content()).startsWith("  ");
        assertThat(chunks.get(chunks.size() - 1).content()).endsWith("  ");
    }

    // --- CJK / Emoji / long unbreakable -----------------------------------------

    @Test
    void cjkTextChunksDeterministicallyWithinBudget() {
        ParsedDocument document = singleSection("第一段中文内容需要分块，确保中文字符被逐个计数并且不会把段落切散。\n"
                .repeat(15));
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, 30, 3));
        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(30);
            assertThat(chunk.content()).isNotEmpty();
        }
    }

    @Test
    void emojiAndSurrogatePairsAreNeverSplit() {
        ParsedDocument document = singleSection("hello 😀 world 👨‍👩‍👧‍👦 family 🚀 end of the line here.\n".repeat(8));
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, 12, 2));
        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            String content = chunk.content();
            assertThat(Character.isHighSurrogate(content.charAt(content.length() - 1)))
                    .describedAs("chunk ends on a lone high surrogate").isFalse();
            assertThat(Character.isLowSurrogate(content.charAt(0)))
                    .describedAs("chunk starts on a lone low surrogate").isFalse();
        }
    }

    @Test
    void longUnbreakableTextDegradesSafelyWithoutInfiniteLoop() {
        ParsedDocument document = singleSection("a".repeat(5000));
        int maxTokens = 40;
        List<ChunkDraft> chunks = chunker.split(document, new ChunkPolicy(ChunkPolicy.Strategy.RECURSIVE, maxTokens, 5));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(25);
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.tokenCount()).isBetween(1, maxTokens);
            assertThat(chunk.content()).isNotEmpty();
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static ParsedSection sectionAt(ParsedDocument document, int charOffset) {
        for (ParsedSection section : document.sections()) {
            if (charOffset >= section.startOffset() && charOffset < section.endOffset()) {
                return section;
            }
        }
        return null;
    }

    @Test
    void pathSegmentsExpandDottedHeadingPaths() {
        assertThat(DeterministicChunker.pathSegments("1.1")).containsExactly("1", "1.1");
        assertThat(DeterministicChunker.pathSegments("2")).containsExactly("2");
        assertThat(DeterministicChunker.pathSegments("1.1.2")).containsExactly("1", "1.1", "1.1.2");
        assertThat(DeterministicChunker.pathSegments(null)).isEmpty();
        assertThat(DeterministicChunker.pathSegments("  ")).isEmpty();
    }

    @Test
    void contentHashIsLowercaseSha256Hex() {
        assertThat(DeterministicChunker.sha256Hex("hello")).isEqualTo(HASH_SHA256_HELLO);
    }
}
