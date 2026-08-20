package com.knowagent.knowledge.document;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The registry selects the unique parser per detected MIME, fails the unknown-MIME path
 * with {@link ErrorCode#UNSUPPORTED_DOCUMENT_TYPE}, and refuses an ambiguous registry at
 * construction. Selection is deterministic and safe for concurrent use.
 */
class ParserRegistryTest {

    private final ParserRegistry registry = new ParserRegistry(List.of(
            new TxtMarkdownParser(ParseProperties.defaults()),
            new PdfParser(ParseProperties.defaults()),
            new DocxParser(ParseProperties.defaults())));

    @Test
    void selectsTheParserForEachSupportedMime() throws IOException {
        assertThat(registry.parsers()).hasSize(3);

        ParsedDocument txt = registry.parse(TestSources.of("text/plain",
                "hello\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(txt.pageCount()).isZero();

        ParsedDocument pdf = registry.parse(TestSources.of("application/pdf",
                TestDocuments.pdf("Page")));
        assertThat(pdf.pageCount()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownMimeWithStableCode() {
        BusinessException failure = catchThrowableOfType(
                () -> registry.parse(TestSources.of("application/zip",
                        "not a document".getBytes(StandardCharsets.UTF_8))),
                BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
        assertThat(failure.getMessage()).isEqualTo("The document type is not supported.");
    }

    @Test
    void closesTheSourceWhenNoParserSupportsItsMime() throws IOException {
        CloseTrackingInputStream tracked = new CloseTrackingInputStream(
                new java.io.ByteArrayInputStream("unsupported".getBytes(StandardCharsets.UTF_8)));

        catchThrowableOfType(() -> registry.parse(TestSources.tracked("application/zip", tracked)),
                BusinessException.class);

        assertThat(tracked.closed()).isTrue();
    }

    @Test
    void normalizesMimeParametersAndCase() throws IOException {
        ParsedDocument document = registry.parse(TestSources.of("TEXT/PLAIN; charset=utf-8",
                "hello\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(document.text()).isEqualTo("hello\n");
    }

    @Test
    void failsAtConstructionWhenTwoParsersClaimTheSameMime() {
        assertThatThrownBy(() -> new ParserRegistry(List.of(
                new FakeParser("text/plain"),
                new FakeParser("text/plain"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unambiguous");
    }

    @Test
    void resolvesDeterministicallyFromManyThreads() throws Exception {
        byte[] markdown = "# Threaded\n\nbody\n".getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(executor.submit(() ->
                        registry.parse(TestSources.of("text/markdown", markdown)).text()));
            }
            for (Future<String> future : futures) {
                assertThat(future.get()).isEqualTo("# Threaded\nbody\n");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FakeParser implements DocumentParser {

        private final String mime;

        FakeParser(String mime) {
            this.mime = mime;
        }

        @Override
        public Set<String> supportedMimeTypes() {
            return Set.of(mime);
        }

        @Override
        public ParsedDocument parse(ParseSource source) {
            return new ParsedDocument(null, "", 0, List.of());
        }
    }
}
