package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * TXT/Markdown parsing: plain text becomes one section, Markdown headings open numbered
 * sections; encodings (UTF-8 BOM, UTF-16 BOM) and line endings are normalized; empty
 * text and over-limit text fail with stable codes; the source stream is always closed.
 */
class TxtMarkdownParserTest {

    private final TxtMarkdownParser parser = new TxtMarkdownParser(ParseProperties.defaults());

    @Test
    void parsesPlainTextAsOneSection() {
        ParsedDocument document = parser.parse(TestSources.of("text/plain",
                "Hello world\nsecond line\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(document.title()).isNull();
        assertThat(document.pageCount()).isZero();
        assertThat(document.text()).isEqualTo("Hello world\nsecond line\n");
        assertThat(document.sections()).hasSize(1);
        ParsedSection section = document.sections().get(0);
        assertThat(section.sectionPath()).isNull();
        assertThat(section.heading()).isNull();
        assertThat(section.content()).isEqualTo("Hello world\nsecond line\n");
        assertThat(section.startOffset()).isZero();
        assertThat(section.endOffset()).isEqualTo(document.text().length());
        assertThat(document.text().substring((int) section.startOffset(), (int) section.endOffset()))
                .isEqualTo(section.content());
    }

    @Test
    void parsesMarkdownHeadingsWithNumberedPaths() {
        ParsedDocument document = parser.parse(TestSources.of("text/markdown", """
                # First

                first body

                ## Child

                child body

                # Second

                second body
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(document.text()).isEqualTo(
                "# First\nfirst body\n## Child\nchild body\n# Second\nsecond body\n");
        List<ParsedSection> sections = document.sections();
        assertThat(sections).hasSize(3);
        assertThat(sections).extracting(ParsedSection::sectionPath).containsExactly("1", "1.1", "2");
        assertThat(sections).extracting(ParsedSection::heading).containsExactly("First", "Child", "Second");
        assertThat(sections.get(0).content()).isEqualTo("# First\nfirst body\n");
        assertThat(sections.get(1).content()).isEqualTo("## Child\nchild body\n");
        assertThat(sections.get(2).content()).isEqualTo("# Second\nsecond body\n");
        assertThat(sections.get(0).startOffset()).isZero();
        assertThat(sections.get(2).endOffset()).isEqualTo(document.text().length());
        for (int i = 1; i < sections.size(); i++) {
            assertThat(sections.get(i).startOffset()).isEqualTo(sections.get(i - 1).endOffset());
            assertThat(document.text().substring((int) sections.get(i).startOffset(),
                    (int) sections.get(i).endOffset())).isEqualTo(sections.get(i).content());
        }
    }

    @Test
    void markdownPreambleBecomesALeadingUnsectionedPart() {
        ParsedDocument document = parser.parse(TestSources.of("text/markdown",
                "Intro paragraph before any heading.\n\n# First Heading\n\nBody.\n"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(document.sections()).hasSize(2);
        assertThat(document.sections().get(0).sectionPath()).isNull();
        assertThat(document.sections().get(0).heading()).isNull();
        assertThat(document.sections().get(0).content()).isEqualTo("Intro paragraph before any heading.\n");
        assertThat(document.sections().get(1).sectionPath()).isEqualTo("1");
        assertThat(document.sections().get(1).content()).isEqualTo("# First Heading\nBody.\n");
    }

    @Test
    void decodesUtf8BomAndNormalizesLineEndings() {
        // The leading U+FEFF is encoded as the UTF-8 BOM (EF BB BF).
        byte[] content = "﻿line one\r\nline two\r\n".getBytes(StandardCharsets.UTF_8);

        ParsedDocument document = parser.parse(TestSources.of("text/plain", content));

        assertThat(document.text()).isEqualTo("line one\nline two\n");
        assertThat(document.sections().get(0).content()).isEqualTo("line one\nline two\n");
    }

    @Test
    void decodesUtf16LeWithBom() {
        // The leading U+FEFF is encoded as the UTF-16LE BOM (FF FE).
        byte[] content = "﻿héllo\n".getBytes(StandardCharsets.UTF_16LE);

        ParsedDocument document = parser.parse(TestSources.of("text/plain", content));

        assertThat(document.text()).isEqualTo("héllo\n");
    }

    @Test
    void rejectsEmptySource() {
        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of("text/plain", new byte[0])),
                BusinessException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.EMPTY_DOCUMENT);
    }

    @Test
    void rejectsContentWithOnlyBlankLines() {
        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of("text/plain", "  \n\n\t\n".getBytes(StandardCharsets.UTF_8))),
                BusinessException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.EMPTY_DOCUMENT);
    }

    @Test
    void rejectsTextOverCharacterLimit() {
        TxtMarkdownParser limited = new TxtMarkdownParser(
                new ParseProperties(0, 0, 0, 5, Duration.ofSeconds(60)));

        BusinessException failure = catchThrowableOfType(
                () -> limited.parse(TestSources.of("text/plain", "abcdef".getBytes(StandardCharsets.UTF_8))),
                BusinessException.class);
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void closesInputStreamOnSuccess() throws java.io.IOException {
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(
                new java.io.ByteArrayInputStream("some text\n".getBytes(StandardCharsets.UTF_8)));

        parser.parse(TestSources.tracked("text/plain", tracked));

        assertThat(tracked.closed()).isTrue();
    }

    @Test
    void closesInputStreamOnEmptySource() throws java.io.IOException {
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(new java.io.ByteArrayInputStream(new byte[0]));

        catchThrowableOfType(() -> parser.parse(TestSources.tracked("text/plain", tracked)),
                BusinessException.class);

        assertThat(tracked.closed()).isTrue();
    }
}
