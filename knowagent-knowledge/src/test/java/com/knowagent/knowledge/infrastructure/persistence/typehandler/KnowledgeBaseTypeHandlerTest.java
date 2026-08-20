package com.knowagent.knowledge.infrastructure.persistence.typehandler;

import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseTypeHandlerTest {

    @Test
    void chunkPolicyRoundTripsThroughJsonb() throws Exception {
        ChunkPolicy policy = new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 500, 40);

        String json = ChunkPolicyJsonbTypeHandler.write(policy);
        ChunkPolicy decoded = ChunkPolicyJsonbTypeHandler.read(json);

        assertThat(json).isEqualTo(
                "{\"strategy\":\"MARKDOWN_HEADING\",\"maxTokens\":500,\"overlapTokens\":40}");
        assertThat(decoded).isEqualTo(policy);
    }

    @Test
    void chunkPolicyRejectsAnInvalidStoredValue() {
        // maxTokens = 0 violates the ChunkPolicy invariant; the compact constructor is
        // invoked during deserialization and surfaces as a persistence failure.
        assertThatThrownBy(() -> ChunkPolicyJsonbTypeHandler.read(
                "{\"strategy\":\"RECURSIVE\",\"maxTokens\":0,\"overlapTokens\":0}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> ChunkPolicyJsonbTypeHandler.read(
                "{\"strategy\":\"RECURSIVE\",\"maxTokens\":100,\"overlapTokens\":100}"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void retrievalConfigRoundTripsThroughJsonb() throws Exception {
        RetrievalConfig config = new RetrievalConfig(25, 0.7, true);

        String json = RetrievalConfigJsonbTypeHandler.write(config);
        RetrievalConfig decoded = RetrievalConfigJsonbTypeHandler.read(json);

        assertThat(json).isEqualTo("{\"topK\":25,\"scoreThreshold\":0.7,\"rerankEnabled\":true}");
        assertThat(decoded).isEqualTo(config);
    }

    @Test
    void retrievalConfigRejectsAnInvalidStoredValue() {
        assertThatThrownBy(() -> RetrievalConfigJsonbTypeHandler.read(
                "{\"topK\":0,\"scoreThreshold\":0.0,\"rerankEnabled\":false}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> RetrievalConfigJsonbTypeHandler.read(
                "{\"topK\":101,\"scoreThreshold\":0.0,\"rerankEnabled\":false}"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> RetrievalConfigJsonbTypeHandler.read(
                "{\"topK\":10,\"scoreThreshold\":1.5,\"rerankEnabled\":false}"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void nullStoredValuesDecodeToNull() throws Exception {
        assertThat(ChunkPolicyJsonbTypeHandler.read(null)).isNull();
        assertThat(RetrievalConfigJsonbTypeHandler.read(null)).isNull();
    }
}
