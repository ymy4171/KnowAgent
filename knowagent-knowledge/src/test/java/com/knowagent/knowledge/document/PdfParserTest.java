package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * PDF parsing: each page becomes a section with its 1-based page number, text order is
 * preserved and character offsets are exact. A blank/scanned PDF is reported as
 * {@link ErrorCode#OCR_REQUIRED} (never fabricated text); corrupt, empty, over-paged and
 * over-char documents fail with stable codes; the source stream is always closed.
 */
class PdfParserTest {

    private static final String MIME = "application/pdf";

    private final PdfParser parser = new PdfParser(ParseProperties.defaults());

    @Test
    void parsesPaginatedPdfWithPageNumbers() throws IOException {
        ParsedDocument document = parser.parse(TestSources.of(MIME,
                TestDocuments.pdf("Page One Alpha", "Page Two Beta", "Page Three Gamma")));

        assertThat(document.pageCount()).isEqualTo(3);
        assertThat(document.sections()).hasSize(3);
        assertThat(document.sections()).extracting(ParsedSection::pageNumber)
                .containsExactly(1, 2, 3);
        assertThat(document.sections()).extracting(ParsedSection::sectionPath)
                .containsOnlyNulls();
        assertThat(document.text().indexOf("Page One Alpha"))
                .isLessThan(document.text().indexOf("Page Two Beta"));
        assertThat(document.text().indexOf("Page Two Beta"))
                .isLessThan(document.text().indexOf("Page Three Gamma"));
        assertThat(document.sections().get(0).startOffset()).isZero();
        assertThat(document.sections().get(2).endOffset()).isEqualTo(document.text().length());
        for (int i = 0; i < document.sections().size(); i++) {
            ParsedSection section = document.sections().get(i);
            if (i > 0) {
                assertThat(section.startOffset()).isEqualTo(document.sections().get(i - 1).endOffset());
            }
            assertThat(document.text().substring((int) section.startOffset(), (int) section.endOffset()))
                    .isEqualTo(section.content());
        }
        assertThat(document.sections().get(0).content()).contains("Page One Alpha");
    }

    @Test
    void carriesPdfTitleFromMetadata() throws IOException {
        ParsedDocument document = parser.parse(TestSources.of(MIME,
                TestDocuments.pdfWithTitle("Annual Report", "Content here")));

        assertThat(document.title()).isEqualTo("Annual Report");
    }

    @Test
    void scannedPdfWithoutTextReportsOcrRequired() throws IOException {
        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of(MIME, TestDocuments.blankPdf())),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.OCR_REQUIRED);
    }

    @Test
    void rejectsCorruptPdf() throws IOException {
        byte[] truncated = Arrays.copyOf(TestDocuments.pdf("Some content"), 20);

        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of(MIME, truncated)),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.CORRUPT_DOCUMENT);
        // The stable message never leaks object keys, file names or filesystem paths.
        assertThat(failure.getMessage()).doesNotContain("object-key", "sample.bin", "\\", "/");
    }

    @Test
    void rejectsEmptySource() {
        BusinessException failure = catchThrowableOfType(
                () -> parser.parse(TestSources.of(MIME, new byte[0])),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.EMPTY_DOCUMENT);
    }

    @Test
    void rejectsPdfWithMorePagesThanAllowed() throws IOException {
        PdfParser limited = new PdfParser(new ParseProperties(0, 1, 0, 0, Duration.ofSeconds(60)));

        BusinessException failure = catchThrowableOfType(
                () -> limited.parse(TestSources.of(MIME, TestDocuments.pdf("Page one", "Page two"))),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void rejectsPdfTextOverCharacterLimit() throws IOException {
        PdfParser limited = new PdfParser(new ParseProperties(0, 0, 0, 10, Duration.ofSeconds(60)));

        BusinessException failure = catchThrowableOfType(
                () -> limited.parse(TestSources.of(MIME,
                        TestDocuments.pdf("This page holds far more than ten characters of text"))),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE);
    }

    @Test
    void closesInputStreamOnSuccess() throws IOException {
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(
                new ByteArrayInputStream(TestDocuments.pdf("Some content")));

        parser.parse(TestSources.tracked(MIME, tracked));

        assertThat(tracked.closed()).isTrue();
    }

    @Test
    void closesInputStreamWhenDocumentIsRejected() throws IOException {
        PdfParser limited = new PdfParser(new ParseProperties(0, 1, 0, 0, Duration.ofSeconds(60)));
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(
                new ByteArrayInputStream(TestDocuments.pdf("Page one", "Page two")));

        catchThrowableOfType(() -> limited.parse(TestSources.tracked(MIME, tracked)),
                BusinessException.class);

        assertThat(tracked.closed()).isTrue();
    }
}
