package com.knowagent.knowledge.knowledgebase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.ChunkPolicy;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tenant's knowledge base: metadata, optional embedding/rerank provider bindings,
 * the chunking policy and the retrieval configuration. {@code slug} is normalized to
 * the lowercase {@code ^[a-z0-9][a-z0-9_-]{0,98}$} shape enforced by the DB CHECK, and
 * the embedding/rerank provider/model pairs must appear together (matching the DB
 * {@code *_pair} CHECKs). Provider ids stay opaque references; nothing here reads
 * provider configuration or secrets.
 */
public record KnowledgeBase(
        UUID id,
        TenantId tenantId,
        String slug,
        String name,
        String description,
        KnowledgeType knowledgeType,
        KnowledgeBaseStatus status,
        UUID embeddingProviderId,
        String embeddingModel,
        UUID rerankProviderId,
        String rerankModel,
        ChunkPolicy chunkPolicy,
        RetrievalConfig retrievalConfig,
        JsonNode metadata,
        UUID createdBy,
        UUID updatedBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,98}$");
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    public KnowledgeBase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(chunkPolicy, "chunkPolicy must not be null");
        Objects.requireNonNull(retrievalConfig, "retrievalConfig must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lowercase and match [a-z0-9][a-z0-9_-]{0,98}");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must contain at most " + MAX_NAME_LENGTH + " characters");
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must contain at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        requirePair(embeddingProviderId, embeddingModel, "embedding");
        requirePair(rerankProviderId, rerankModel, "rerank");
        if (!metadata.isObject()) {
            throw new IllegalArgumentException("metadata must be a JSON object");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        metadata = metadata.deepCopy();
    }

    /** Normalizes a raw slug to the canonical lowercase form enforced by the DB CHECK. */
    public static String normalizeSlug(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether a normalized slug satisfies the {@code slug} DB regex. */
    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches();
    }

    public static JsonNode emptyMetadata() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static void requirePair(UUID providerId, String model, String role) {
        if (providerId == null && model == null) {
            return;
        }
        if (providerId == null || model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    role + " provider and model must be configured together");
        }
    }

    @Override
    public String toString() {
        return "KnowledgeBase[id=" + id + ", tenantId=" + tenantId + ", slug=" + slug
                + ", status=" + status + ", version=" + version + "]";
    }
}
