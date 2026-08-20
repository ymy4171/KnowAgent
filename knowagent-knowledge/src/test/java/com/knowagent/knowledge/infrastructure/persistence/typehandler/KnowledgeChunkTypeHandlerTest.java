package com.knowagent.knowledge.infrastructure.persistence.typehandler;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structured JSONB mapping for the {@code knowledge_chunks} {@code section_path} array and
 * {@code metadata} object columns: values round-trip through the JSON text and corrupt
 * stored JSON surfaces as a persistence failure.
 */
class KnowledgeChunkTypeHandlerTest {

    @Test
    void sectionPathRoundTripsThroughJsonb() throws Exception {
        List<String> path = List.of("1", "1.1");

        String json = StringListJsonbTypeHandler.write(path);
        List<String> decoded = StringListJsonbTypeHandler.read(json);

        assertThat(json).isEqualTo("[\"1\",\"1.1\"]");
        assertThat(decoded).isEqualTo(path);
    }

    @Test
    void sectionPathRejectsCorruptStoredJson() {
        assertThatThrownBy(() -> StringListJsonbTypeHandler.read("{\"not\":\"an array\"}"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void metadataRoundTripsThroughJsonb() throws Exception {
        Map<String, String> metadata = Map.of("token_estimator", "char-run-v1");

        String json = StringMapJsonbTypeHandler.write(metadata);
        Map<String, String> decoded = StringMapJsonbTypeHandler.read(json);

        assertThat(json).isEqualTo("{\"token_estimator\":\"char-run-v1\"}");
        assertThat(decoded).isEqualTo(metadata);
    }

    @Test
    void metadataRejectsCorruptStoredJson() {
        assertThatThrownBy(() -> StringMapJsonbTypeHandler.read("[\"not\",\"an\",\"object\"]"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void nullStoredValuesDecodeToNull() throws Exception {
        assertThat(StringListJsonbTypeHandler.read(null)).isNull();
        assertThat(StringMapJsonbTypeHandler.read(null)).isNull();
    }
}
