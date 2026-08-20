package com.knowagent.knowledge.chunk;

import com.knowagent.knowledge.document.ParsedDocument;
import com.knowagent.knowledge.document.ParsedSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Deterministic chunker implementing all three policies of {@link ChunkPolicy.Strategy}.
 * For the same {@link ParsedDocument} and {@link ChunkPolicy} it always produces the same
 * ordered chunks with the same content and hashes; it never emits an empty chunk and never
 * loops. The chunk budget ({@code chunkSize}) and overlap are always interpreted in the
 * policy's unit (tokens), with {@code overlap < chunkSize} guaranteed by the policy's own
 * invariant and by the overlap clamp below.
 *
 * <ul>
 *   <li>{@code RECURSIVE}: splits the whole text preferring paragraph, then line, then
 *       sentence boundaries; over-budget gaps degrade to word or safe code-point cuts.
 *   <li>{@code MARKDOWN_HEADING}: treats each {@code ParsedSection} (heading) as a hard
 *       boundary - a chunk never crosses a section - then splits within the section using
 *       the same boundary hierarchy.
 *   <li>{@code TOKEN_WINDOW}: fixed token windows sliding by {@code maxTokens - overlapTokens}
 *       tokens, ignoring layout; overlap is exact in token space.
 * </ul>
 *
 * <p>Every chunk carries the reference metadata of the {@code ParsedSection} covering its
 * start offset: {@code pageNumber} and {@code sectionPath} (the dotted heading path expanded
 * into its level segments, e.g. {@code "1.1"} → {@code ["1","1.1"]}), plus the token-estimator
 * algorithm version in {@code metadata}. Chunks cover the whole document; the overlap only
 * re-reads a bounded tail of the previous chunk, so consecutive chunks may share content.
 */
public final class DeterministicChunker implements Chunker {

    private final TokenCounter tokenCounter;

    public DeterministicChunker(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
    }

    @Override
    public List<ChunkDraft> split(ParsedDocument document, ChunkPolicy policy) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        TokenStream stream = tokenCounter.tokenize(document.text());
        List<ChunkDraft> chunks = new ArrayList<>();
        switch (policy.strategy()) {
            case TOKEN_WINDOW -> chunkByTokenWindows(document, stream, policy, chunks);
            case MARKDOWN_HEADING -> {
                int sectionTokenOffset = 0;
                for (ParsedSection section : document.sections()) {
                    TokenStream sectionStream = tokenCounter.tokenize(section.content());
                    chunkByBoundaries(document, section.content(), sectionStream, policy,
                            0, section.content().length(), Math.toIntExact(section.startOffset()),
                            sectionTokenOffset, chunks);
                    sectionTokenOffset = Math.addExact(sectionTokenOffset, sectionStream.count());
                }
            }
            case RECURSIVE -> chunkByBoundaries(document, document.text(), stream, policy,
                    0, document.text().length(), 0, 0, chunks);
        }
        return List.copyOf(chunks);
    }

    private void chunkByTokenWindows(ParsedDocument document, TokenStream stream, ChunkPolicy policy,
                                     List<ChunkDraft> chunks) {
        if (stream.count() == 0) {
            emit(document, stream, policy, 0, document.text().length(), 0, 0, chunks);
            return;
        }
        int maxTokens = policy.maxTokens();
        int step = maxTokens - policy.overlapTokens(); // > 0 by the policy invariant
        int tokenIndex = 0;
        while (tokenIndex < stream.count()) {
            int endToken = Math.min(tokenIndex + maxTokens, stream.count());
            int startChar = tokenIndex == 0 ? 0 : stream.tokenStartChar(tokenIndex);
            // Assign separators to the preceding window. With zero overlap this makes
            // consecutive chunks contiguous and preserves leading/trailing whitespace.
            int endChar = endToken >= stream.count()
                    ? document.text().length()
                    : stream.tokenStartChar(endToken);
            emit(document, stream, policy, startChar, endChar, 0, 0, chunks);
            if (endToken >= stream.count()) {
                break;
            }
            tokenIndex += step;
        }
    }

    private void chunkByBoundaries(ParsedDocument document, String text, TokenStream stream, ChunkPolicy policy,
                                   int segmentStart, int segmentEnd, int documentCharOffset,
                                   int documentTokenOffset, List<ChunkDraft> chunks) {
        NavigableSet<Integer> cuts = preferredCuts(text, segmentStart, segmentEnd);
        int start = segmentStart;
        while (start < segmentEnd) {
            int end = chooseCut(text, stream, policy, cuts, start, segmentEnd);
            emit(document, stream, policy, start, end,
                    documentCharOffset, documentTokenOffset, chunks);
            if (end >= segmentEnd) {
                break;
            }
            // overlapStart guarantees strict progress (at least one token) and never
            // crosses the segment boundary, so the loop always terminates.
            start = overlapStart(stream, policy, start, end, segmentStart, segmentEnd);
        }
    }

    /** Furthest cut within the token budget, preferring the whole remainder when it fits. */
    private int chooseCut(String text, TokenStream stream, ChunkPolicy policy, NavigableSet<Integer> cuts,
                          int start, int segmentEnd) {
        int maxTokens = policy.maxTokens();
        if (stream.tokenCountAt(segmentEnd) - stream.tokenCountAt(start) <= maxTokens) {
            return segmentEnd;
        }
        int cut = start;
        for (int candidate : cuts.tailSet(start + 1)) {
            if (candidate >= segmentEnd) {
                break;
            }
            if (stream.tokenCountAt(candidate) - stream.tokenCountAt(start) > maxTokens) {
                break;
            }
            cut = candidate;
        }
        return cut > start ? cut : safeSplit(text, stream, policy, start, segmentEnd);
    }

    /**
     * Safe degradation for a span with no preferred cut within budget (a long unbreakable
     * run): split at a token boundary after {@code maxTokens} tokens, preferring the
     * furthest whitespace position inside the window so words stay intact where possible.
     */
    private int safeSplit(String text, TokenStream stream, ChunkPolicy policy, int start, int segmentEnd) {
        int maxTokens = policy.maxTokens();
        int firstToken = stream.tokenCountAt(start);
        int target = Math.min(firstToken + maxTokens, stream.count());
        // End at the last token of the window: tokenEndChar(target) would sit just past
        // token `target` and therefore include maxTokens + 1 tokens (tokenCountAt counts
        // tokens whose endChar is <= the offset). Mirrors chunkByTokenWindows.
        int cut = target >= stream.count() ? segmentEnd : stream.tokenEndChar(target - 1);
        for (int position = cut - 1; position > start; position--) {
            if (Character.isWhitespace(text.charAt(position))) {
                cut = position + 1;
                break;
            }
        }
        if (cut <= start) {
            cut = firstToken < stream.count() ? stream.tokenEndChar(firstToken) : segmentEnd;
            if (cut <= start) {
                cut = segmentEnd;
            }
        }
        return Math.min(cut, segmentEnd);
    }

    /**
     * Start of the next chunk: the tail {@code overlapTokens} tokens of the current chunk,
     * clamped so overlap never equals the chunk (at least one token of progress) and never
     * crosses the segment start or end.
     */
    private int overlapStart(TokenStream stream, ChunkPolicy policy, int start, int end,
                             int segmentStart, int segmentEnd) {
        int tokensInChunk = stream.tokenCountAt(end) - stream.tokenCountAt(start);
        int overlap = Math.min(policy.overlapTokens(), Math.max(tokensInChunk - 1, 0));
        int nextToken = stream.tokenCountAt(end) - overlap;
        int nextStart = nextToken >= stream.count() ? end : stream.tokenStartChar(nextToken);
        if (nextStart <= start) {
            int tokenOfStart = stream.tokenCountAt(start);
            nextStart = tokenOfStart < stream.count() ? stream.tokenEndChar(tokenOfStart) : end;
        }
        if (nextStart >= segmentEnd) {
            return segmentEnd;
        }
        return Math.max(nextStart, segmentStart);
    }

    /** Preferred cut positions in {@code (lo, hi)}: after newlines and at sentence / word boundaries. */
    private static NavigableSet<Integer> preferredCuts(String text, int lo, int hi) {
        NavigableSet<Integer> cuts = new TreeSet<>();
        int index = lo;
        while (index < hi) {
            char c = text.charAt(index);
            if (c == '\n') {
                cuts.add(index + 1);
            } else if (isSentenceEnd(c) && index + 1 < text.length()) {
                char next = text.charAt(index + 1);
                if (next < hi && (Character.isWhitespace(next) || isIdeographic(next))) {
                    cuts.add(index + 1);
                }
            } else if (Character.isWhitespace(c)) {
                int end = index;
                while (end < hi && Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                cuts.add(end);
                index = end;
                continue;
            }
            index++;
        }
        return cuts;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？';
    }

    private void emit(ParsedDocument document, TokenStream stream, ChunkPolicy policy,
                      int start, int end, int documentCharOffset, int documentTokenOffset,
                      List<ChunkDraft> chunks) {
        if (start >= end) {
            return; // defensive: never emit an empty chunk
        }
        int documentStart = Math.addExact(documentCharOffset, start);
        int documentEnd = Math.addExact(documentCharOffset, end);
        String content = document.text().substring(documentStart, documentEnd);
        ParsedSection section = sectionAt(document, documentStart);
        int tokenCount = stream.tokenCountAt(end) - stream.tokenCountAt(start);
        Map<String, String> metadata = Map.of(
                "token_estimator", tokenCounter.algorithmVersion(),
                "chunk_strategy", policy.strategy().name());
        chunks.add(new ChunkDraft(
                chunks.size(),
                content,
                sha256Hex(content),
                tokenCount,
                documentStart,
                documentEnd,
                Math.addExact(documentTokenOffset, stream.tokenCountAt(start)),
                Math.addExact(documentTokenOffset, stream.tokenCountAt(end)),
                section == null ? null : section.pageNumber(),
                pathSegments(section == null ? null : section.sectionPath()),
                metadata));
    }

    /** The section covering {@code charOffset}; sections partition the text, so always found. */
    private static ParsedSection sectionAt(ParsedDocument document, int charOffset) {
        for (ParsedSection section : document.sections()) {
            if (charOffset >= section.startOffset() && charOffset < section.endOffset()) {
                return section;
            }
        }
        return null;
    }

    /**
     * Expands a dotted heading path into its level segments, e.g. {@code "1.1"} →
     * {@code ["1","1.1"]}; null or blank yields the empty list (the JSONB {@code '[]'} default).
     */
    static List<String> pathSegments(String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return List.of();
        }
        String[] levels = dottedPath.split("\\.");
        List<String> segments = new ArrayList<>(levels.length);
        StringBuilder prefix = new StringBuilder();
        for (String level : levels) {
            if (!prefix.isEmpty()) {
                prefix.append('.');
            }
            prefix.append(level);
            segments.add(prefix.toString());
        }
        return List.copyOf(segments);
    }

    public static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    private static boolean isIdeographic(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF)
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F)
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F)
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)
                || (codePoint >= 0x2CEB0 && codePoint <= 0x2EBEF)
                || (codePoint >= 0x30000 && codePoint <= 0x3134F)
                || (codePoint >= 0x3000 && codePoint <= 0x303F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
    }
}
