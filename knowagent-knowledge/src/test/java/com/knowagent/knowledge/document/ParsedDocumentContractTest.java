package com.knowagent.knowledge.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsedDocumentContractTest {

    @Test
    void rejectsSectionWhoseContentDoesNotMatchItsTextSlice() {
        ParsedSection section = new ParsedSection("1", "Heading", "other", null,
                0, 5, Map.of());

        assertThatThrownBy(() -> new ParsedDocument("Secret title", "value", 0, List.of(section)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text slice");
    }

    @Test
    void rejectsInvalidSectionRangeLengthAndPageNumber() {
        assertThatThrownBy(() -> new ParsedSection(null, null, "text", null,
                0, 3, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range length");
        assertThatThrownBy(() -> new ParsedSection(null, null, "text", 0,
                0, 4, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }

    @Test
    void documentValueObjectsDoNotExposeSourceOrExtractedContentInToString() throws Exception {
        byte[] bytes = "secret body".getBytes(StandardCharsets.UTF_8);
        ParseSource source = new ParseSource("tenants/secret/object", "secret-file.txt",
                "text/plain", bytes.length, new ByteArrayInputStream(bytes));
        ParsedSection section = new ParsedSection("1", "Secret heading", "secret body", null,
                0, 11, Map.of("internal", "secret metadata"));
        ParsedDocument document = new ParsedDocument("Secret title", "secret body", 0,
                List.of(section));

        assertThat(source.toString())
                .doesNotContain("tenants/secret/object", "secret-file.txt", "secret body")
                .contains("[REDACTED]");
        assertThat(section.toString())
                .doesNotContain("Secret heading", "secret body", "secret metadata")
                .contains("heading=[REDACTED]", "content=[REDACTED]", "metadata=[REDACTED]");
        assertThat(document.toString())
                .doesNotContain("Secret title", "secret body")
                .contains("text=[REDACTED]", "textLength=11", "sectionCount=1");
        source.content().close();
    }
}
