package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * DOCX parsing: Heading-styled paragraphs open numbered sections with exact offsets and
 * the heading text; table cells contribute their text in reading order; the title comes
 * from OOXML core properties. Empty, corrupt, over-uncompressed (zip-bomb guard) and
 * over-char documents fail with stable codes; the source stream is always closed.
 */
class DocxParserTest {

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocxParser parser = new DocxParser(ParseProperties.defaults());

    @Test
    void parsesDocxWithHeadingHierarchy() throws IOException {
        ParsedDocument document = parser.parse(TestSources.of(MIME, TestDocuments.docx(doc -> {
            TestDocuments.heading(doc, 1, "Chapter One");
            TestDocuments.paragraph(doc, "Opening.");
            TestDocuments.heading(doc, 2, "Section A");
            TestDocuments.paragraph(doc, "Alpha body.");
            TestDocuments.heading(doc, 1, "Chapter Two");
            TestDocuments.paragraph(doc, "Second chapter.");
        })));

        assertThat(document.title()).isNull();
        assertThat(document.pageCount()).isZero();
        assertThat(document.text()).isEqualTo(
                "Chapter One\nOpening.\nSection A\nAlpha body.\nChapter Two\nSecond chapter.\n");
        List<ParsedSection> sections = document.sections();
        assertThat(sections).hasSize(3);
        assertThat(sections).extracting(ParsedSection::sectionPath).containsExactly("1", "1.1", "2");
        assertThat(sections).extracting(ParsedSection::heading)
                .containsExactly("Chapter One", "Section A", "Chapter Two");
        assertThat(sections.get(0).content()).isEqualTo("Chapter One\nOpening.\n");
        assertThat(sections.get(1).content()).isEqualTo("Section A\nAlpha body.\n");
        assertThat(sections.get(2).content()).isEqualTo("Chapter Two\nSecond chapter.\n");
        assertThat(sections.get(0).startOffset()).isZero();
        assertThat(sections.get(2).endOffset()).isEqualTo(document.text().length());
        for (int i = 1; i < sections.size(); i++) {
            assertThat(sections.get(i).startOffset()).isEqualTo(sections.get(i - 1).endOffset());
            assertThat(document.text().substring((int) sections.get(i).startOffset(),
                    (int) sections.get(i).endOffset())).isEqualTo(sections.get(i).content());
        }
    }

    @Test
    void recognizesLocalizedHeadingStyleNameWhenTheStyleIdIsOpaque() throws IOException {
        ParsedDocument document = parser.parse(TestSources.of(MIME, TestDocuments.docx(doc -> {
            TestDocuments.localizedHeading(doc, 2, "Localized heading");
            TestDocuments.paragraph(doc, "Body.");
        })));

        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().get(0).sectionPath()).isEqualTo("1");
        assertThat(document.sections().get(0).heading()).isEqualTo("Localized heading");
    }

    @Test
    void includesTableTextInReadingOrder() throws IOException {
        ParsedDocument document = parser.parse(TestSources.of(MIME, TestDocuments.docx(doc -> {
            TestDocuments.paragraph(doc, "Before table.");
            TestDocuments.table(doc, new String[][]{{"cell-one", "cell-two"}});
            TestDocuments.paragraph(doc, "After table.");
        })));

        assertThat(document.text()).contains("cell-one\tcell-two");
        assertThat(document.text().indexOf("Before table."))
                .isLessThan(document.text().indexOf("cell-one"));
        assertThat(document.text().indexOf("cell-two"))
                .isLessThan(document.text().indexOf("After table."));
    }

    @Test
    void carriesDocxTitleFromCoreProperties() throws IOException {
        ParsedDocument document = parser.parse(TestSources.of(MIME, TestDocuments.docx(doc -> {
            doc.getProperties().getCoreProperties().setTitle("Quarterly Report");
            TestDocuments.paragraph(doc, "Body.");
        })));

        assertThat(document.title()).isEqualTo("Quarterly Report");
    }

    @Test
    void rejectsDocxWithNoText() throws IOException {
        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of(MIME, TestDocuments.docx(doc -> {
                }))),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.EMPTY_DOCUMENT);
    }

    @Test
    void rejectsCorruptDocx() {
        byte[] garbage = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of(MIME, garbage)),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.CORRUPT_DOCUMENT);
        assertThat(failure.getMessage()).doesNotContain("object-key", "sample.bin", "\\", "/");
    }

    @Test
    void rejectsDocxOverUncompressedLimit() throws IOException {
        DocxParser limited = new DocxParser(
                new ParseProperties(0, 0, 1024, 0, Duration.ofSeconds(60)));
        byte[] big = TestDocuments.docx(doc -> TestDocuments.paragraph(doc, "x".repeat(5000)));

        BusinessException failure = catchThrowableOfType(
                () -> limited.parse(TestSources.of(MIME, big)),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void rejectsCumulativeZipExpansionEvenWhenEachEntryIsBelowTheLimit() throws IOException {
        DocxParser limited = new DocxParser(
                new ParseProperties(0, 0, 1000, 0, Duration.ofSeconds(60)));

        BusinessException failure = catchThrowableOfType(
                () -> limited.parse(TestSources.of(MIME, TestDocuments.zipEntries(2, 600))),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void rejectsDocxTextOverCharacterLimit() throws IOException {
        DocxParser limited = new DocxParser(
                new ParseProperties(0, 0, 0, 10, Duration.ofSeconds(60)));

        BusinessException failure = catchThrowableOfType(
                () -> limited.parse(TestSources.of(MIME, TestDocuments.docx(doc ->
                        TestDocuments.paragraph(doc, "This paragraph is longer than ten characters")))),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void closesInputStreamOnSuccess() throws IOException {
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(
                new ByteArrayInputStream(TestDocuments.docx(doc -> TestDocuments.paragraph(doc, "Body."))));

        parser.parse(TestSources.tracked(MIME, tracked));

        assertThat(tracked.closed()).isTrue();
    }

    @Test
    void closesInputStreamWhenDocxIsCorrupt() throws IOException {
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3}));

        catchThrowableOfType(() -> parser.parse(TestSources.tracked(MIME, tracked)),
                BusinessException.class);

        assertThat(tracked.closed()).isTrue();
    }
}
