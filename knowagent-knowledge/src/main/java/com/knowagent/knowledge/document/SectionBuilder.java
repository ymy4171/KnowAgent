package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ParsedDocument} from a sequence of lines, opening a new section
 * whenever a line carries a heading level (Markdown {@code #} or a DOCX Heading style).
 * Each line is appended to the canonical {@code text}; sections record their
 * heading-path (e.g. {@code "1"}, {@code "1.1"}), heading text and exact character
 * offsets, so the produced sections always partition the text contiguously. Blank lines
 * are skipped by the callers; this builder only sees meaningful lines.
 */
final class SectionBuilder {

    private final ParseBudget budget;
    private final long maxCharacters;
    private final StringBuilder text = new StringBuilder();
    private final List<ParsedSection> sections = new ArrayList<>();
    private final List<LevelCount> stack = new ArrayList<>();
    private final StringBuilder body = new StringBuilder();
    private Long bodyStart;
    private String openHeading;
    private String openPath;

    SectionBuilder(long maxCharacters, ParseBudget budget) {
        this.maxCharacters = maxCharacters;
        this.budget = budget;
    }

    /**
     * Appends one line to the canonical text. When {@code level} is non-null the line
     * starts a new section (its own content, path and heading); otherwise it continues
     * the current section.
     */
    void appendLine(String contentLine, Integer level, String heading) {
        budget.checkTime();
        if (level != null) {
            closeBody();
            openHeading = heading;
            openPath = nextPath(stack, level);
            bodyStart = (long) text.length();
            body.append(contentLine).append('\n');
        } else {
            if (bodyStart == null) {
                bodyStart = (long) text.length();
            }
            body.append(contentLine).append('\n');
        }
        text.append(contentLine).append('\n');
        if ((long) text.length() > maxCharacters) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "The document text exceeds the maximum allowed size.");
        }
    }

    /** The length of the canonical text built so far. */
    long textLength() {
        return text.length();
    }

    ParsedDocument finish(String title, int pageCount) {
        closeBody();
        return new ParsedDocument(title, text.toString(), pageCount, sections);
    }

    private void closeBody() {
        if (body.length() > 0) {
            // The body is exactly the text suffix from bodyStart (every line keeps its
            // trailing '\n'), so keeping content identical to that suffix lets sections
            // partition the whole text contiguously with no gaps.
            long contentLength = body.length();
            sections.add(new ParsedSection(openPath, openHeading, body.toString(), null,
                    bodyStart, bodyStart + contentLength, Map.of()));
            body.setLength(0);
        }
        bodyStart = null;
    }

    /**
     * Classic outline numbering for heading levels: increment the counter at the given
     * level, reset deeper levels, and join the active counters with {@code '.'}.
     */
    private static String nextPath(List<LevelCount> stack, int level) {
        while (!stack.isEmpty() && stack.get(stack.size() - 1).level() > level) {
            stack.remove(stack.size() - 1);
        }
        if (!stack.isEmpty() && stack.get(stack.size() - 1).level() == level) {
            LevelCount top = stack.remove(stack.size() - 1);
            stack.add(new LevelCount(top.level(), top.count() + 1));
        } else {
            stack.add(new LevelCount(level, 1));
        }
        return stack.stream().map(count -> Integer.toString(count.count()))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private record LevelCount(int level, int count) {
    }
}
