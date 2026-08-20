package com.knowagent.knowledge.infrastructure.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Milvus connectivity and collection contract ({@code knowagent.vector.milvus.*}),
 * bound from the environment. The adapter is only enabled when {@code uri} is
 * configured (mirroring the MinIO pattern), so the API/worker boot fine without
 * Docker/Milvus; when enabled, a blank {@code uri} or an invalid dimension/collection
 * fails fast at startup instead of surfacing as an opaque SDK error later.
 *
 * <p>The similarity metric is fixed to COSINE by the collection contract (ADR-0005)
 * and is therefore not configurable. The index type and its parameters are fixed by
 * configuration; {@code m}/{@code efConstruction} only apply to HNSW and
 * {@code searchEf} only applies to HNSW search, all other index types ignore them.
 *
 * <p>{@code toString()} only renders the connectivity host and the collection contract
 * - it never renders {@code username}/{@code password}/{@code token}.
 */
@ConfigurationProperties(prefix = "knowagent.vector.milvus")
public record MilvusVectorProperties(
        String uri,
        String username,
        String password,
        String token,
        String databaseName,
        String collectionName,
        int dimension,
        String indexType,
        int m,
        int efConstruction,
        int searchEf,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration rpcDeadline,
        @DefaultValue("10s") Duration searchTimeout,
        @DefaultValue("30s") Duration writeTimeout,
        @DefaultValue("30s") Duration deleteTimeout,
        @DefaultValue("120s") Duration initTimeout) {

    public static final String DEFAULT_COLLECTION = "knowledge_chunks";
    /** Milvus VARCHAR primary-key length for PostgreSQL chunk UUID strings (36 chars). */
    public static final int UUID_FIELD_MAX_LENGTH = 64;
    /** Milvus VARCHAR length for the embedding-model-spec scalar. */
    public static final int MODEL_SPEC_FIELD_MAX_LENGTH = 512;
    /** Milvus 2.5 hard limit for FLOAT_VECTOR dimensions. */
    public static final int MAX_DIMENSION = 65536;

    public static final String INDEX_HNSW = "HNSW";
    public static final String INDEX_FLAT = "FLAT";
    public static final String INDEX_AUTOINDEX = "AUTOINDEX";
    private static final Set<String> SUPPORTED_INDEX_TYPES = Set.of(INDEX_HNSW, INDEX_FLAT, INDEX_AUTOINDEX);

    public MilvusVectorProperties {
        if (isBlank(uri)) {
            throw new IllegalArgumentException("knowagent.vector.milvus.uri must be configured when Milvus is enabled");
        }
        collectionName = isBlank(collectionName) ? DEFAULT_COLLECTION : collectionName;
        if (collectionName.length() > 255) {
            throw new IllegalArgumentException("knowagent.vector.milvus.collection-name must not exceed 255 characters");
        }
        if (dimension < 1 || dimension > MAX_DIMENSION) {
            throw new IllegalArgumentException("knowagent.vector.milvus.dimension must be in [1, " + MAX_DIMENSION + "]");
        }
        if (isBlank(indexType)) {
            indexType = INDEX_HNSW;
        }
        indexType = indexType.toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_INDEX_TYPES.contains(indexType)) {
            throw new IllegalArgumentException("knowagent.vector.milvus.index-type must be one of " + SUPPORTED_INDEX_TYPES);
        }
        if (m < 1) {
            throw new IllegalArgumentException("knowagent.vector.milvus.m must be >= 1");
        }
        if (efConstruction < 1) {
            throw new IllegalArgumentException("knowagent.vector.milvus.ef-construction must be >= 1");
        }
        if (searchEf < 1) {
            throw new IllegalArgumentException("knowagent.vector.milvus.search-ef must be >= 1");
        }
        requirePositiveTimeout(connectTimeout, "connectTimeout");
        requirePositiveTimeout(rpcDeadline, "rpcDeadline");
        requirePositiveTimeout(searchTimeout, "searchTimeout");
        requirePositiveTimeout(writeTimeout, "writeTimeout");
        requirePositiveTimeout(deleteTimeout, "deleteTimeout");
        requirePositiveTimeout(initTimeout, "initTimeout");
    }

    private static void requirePositiveTimeout(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name + " must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "MilvusVectorProperties[uri=" + uri
                + ", username=[REDACTED], password=[REDACTED], token=[REDACTED]"
                + ", databaseName=" + databaseName
                + ", collectionName=" + collectionName
                + ", dimension=" + dimension
                + ", indexType=" + indexType
                + ", m=" + m + ", efConstruction=" + efConstruction
                + ", searchEf=" + searchEf
                + ", connectTimeout=" + connectTimeout + ", rpcDeadline=" + rpcDeadline
                + ", searchTimeout=" + searchTimeout + ", writeTimeout=" + writeTimeout
                + ", deleteTimeout=" + deleteTimeout + ", initTimeout=" + initTimeout + "]";
    }
}
