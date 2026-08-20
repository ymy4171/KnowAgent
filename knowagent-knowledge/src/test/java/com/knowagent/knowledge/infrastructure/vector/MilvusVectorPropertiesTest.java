package com.knowagent.knowledge.infrastructure.vector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the Milvus configuration contract: a blank uri or collection, an invalid
 * dimension, an unsupported index type or a non-positive timeout refuses startup,
 * and toString never renders credentials.
 */
class MilvusVectorPropertiesTest {

    private static final String URI = "http://localhost:19530";

    @Test
    void acceptsValidConfigurationWithDefaults() {
        MilvusVectorProperties properties = new MilvusVectorProperties(
                URI, null, null, null, null, null, 1536, null, 16, 64, 64,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(120));
        assertThat(properties.collectionName()).isEqualTo(MilvusVectorProperties.DEFAULT_COLLECTION);
        assertThat(properties.indexType()).isEqualTo(MilvusVectorProperties.INDEX_HNSW);
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankUriIsRejected(String uri) {
        assertThatThrownBy(() -> valid(uri, null, 1536))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uri");
    }

    @Test
    void blankCollectionFallsBackToDefaultAndOversizedIsRejected() {
        assertThat(valid(URI, null, 1536).collectionName())
                .isEqualTo(MilvusVectorProperties.DEFAULT_COLLECTION);
        assertThatThrownBy(() -> new MilvusVectorProperties(
                URI, null, null, null, null, "x".repeat(256), 1536, "HNSW",
                16, 64, 64, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection-name");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65537})
    void invalidDimensionIsRejected(int dimension) {
        assertThatThrownBy(() -> valid(URI, null, dimension))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void indexTypeIsNormalizedAndRestricted() {
        assertThat(valid(URI, null, 1536, "hnsw").indexType()).isEqualTo("HNSW");
        assertThat(valid(URI, null, 1536, "").indexType()).isEqualTo(MilvusVectorProperties.INDEX_HNSW);
        assertThatThrownBy(() -> valid(URI, null, 1536, "IVF_FLAT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index-type");
    }

    @Test
    void nonPositiveHnswParametersAndTimeoutsAreRejected() {
        assertThatThrownBy(() -> valid(URI, null, 1536, "HNSW", 0, 64, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid(URI, null, 1536, "HNSW", 16, 0, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid(URI, null, 1536, "HNSW", 16, 64, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid(URI, null, 1536, "HNSW", 16, 64, 64, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverRendersCredentials() {
        MilvusVectorProperties properties = valid(URI, "secret-user", 1536);
        assertThat(properties.toString())
                .doesNotContain("secret-user")
                .doesNotContain("secret-password")
                .doesNotContain("secret-token")
                .contains("[REDACTED]");
    }

    private static MilvusVectorProperties valid(String uri, String username, int dimension) {
        return valid(uri, username, dimension, "HNSW");
    }

    private static MilvusVectorProperties valid(String uri, String username, int dimension, String indexType) {
        return valid(uri, username, dimension, indexType, 16, 64, 64);
    }

    private static MilvusVectorProperties valid(String uri, String username, int dimension, String indexType,
                                                int m, int efConstruction, int searchEf) {
        return valid(uri, username, dimension, indexType, m, efConstruction, searchEf, Duration.ofSeconds(5));
    }

    private static MilvusVectorProperties valid(String uri, String username, int dimension, String indexType,
                                                int m, int efConstruction, int searchEf, Duration connectTimeout) {
        return new MilvusVectorProperties(
                uri, username, "secret-password", "secret-token", null, null, dimension, indexType,
                m, efConstruction, searchEf, connectTimeout, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(120));
    }
}
