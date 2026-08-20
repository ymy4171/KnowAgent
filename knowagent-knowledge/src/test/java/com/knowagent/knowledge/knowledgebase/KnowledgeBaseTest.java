package com.knowagent.knowledge.knowledgebase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void constructsWithProvidersConfiguredInPairsOrNotAtAll() {
        UUID embedding = UUID.randomUUID();
        UUID rerank = UUID.randomUUID();

        KnowledgeBase paired = base(b -> b
                .embeddingProviderId(embedding).embeddingModel("text-embedding-3")
                .rerankProviderId(rerank).rerankModel("bge-reranker-v2"));
        assertThat(paired.embeddingProviderId()).isEqualTo(embedding);
        assertThat(paired.rerankModel()).isEqualTo("bge-reranker-v2");

        KnowledgeBase none = base(b -> {});
        assertThat(none.embeddingProviderId()).isNull();
        assertThat(none.rerankModel()).isNull();
    }

    @Test
    void rejectsHalfConfiguredProviderModelPairs() {
        assertThatThrownBy(() -> base(b -> b.embeddingProviderId(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider and model must be configured together");
        assertThatThrownBy(() -> base(b -> b.embeddingModel("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider and model must be configured together");
        assertThatThrownBy(() -> base(b -> b.rerankProviderId(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider and model must be configured together");
    }

    @Test
    void rejectsInvalidSlugShape() {
        assertThatThrownBy(() -> base(b -> b.slug("UPPER"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.slug("with space"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.slug(""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.slug("a".repeat(100)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesSlugAndValidatesBoundaries() {
        assertThat(KnowledgeBase.normalizeSlug("  HR-Manual ")).isEqualTo("hr-manual");
        assertThat(KnowledgeBase.normalizeSlug(null)).isNull();
        assertThat(KnowledgeBase.isValidSlug("a")).isTrue();
        assertThat(KnowledgeBase.isValidSlug("a1-_b")).isTrue();
        assertThat(KnowledgeBase.isValidSlug("a".repeat(99))).isTrue();
        assertThat(KnowledgeBase.isValidSlug("a".repeat(100))).isFalse();
        assertThat(KnowledgeBase.isValidSlug(null)).isFalse();
    }

    @Test
    void rejectsBlankOrOverlongNameAndOverlongDescription() {
        assertThatThrownBy(() -> base(b -> b.name("  "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.name("x".repeat(256)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.description("x".repeat(10_001))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonObjectMetadataAndNegativeVersion() {
        assertThatThrownBy(() -> base(b -> b.metadata(JsonNodeFactory.instance.arrayNode())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(b -> b.version(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesMetadataSoTheAggregateCannotBeMutatedFromOutside() {
        ObjectNode original = JsonNodeFactory.instance.objectNode().put("owner", "hr");
        KnowledgeBase knowledgeBase = base(b -> b.metadata(original));

        original.put("owner", "changed");

        assertThat(knowledgeBase.metadata().get("owner").asText()).isEqualTo("hr");
    }

    @Test
    void statusTransitionsAreCentralizedAndForwardOnly() {
        assertThat(KnowledgeBaseStatus.ACTIVE.canTransitionTo(KnowledgeBaseStatus.DISABLED)).isTrue();
        assertThat(KnowledgeBaseStatus.DISABLED.canTransitionTo(KnowledgeBaseStatus.ACTIVE)).isTrue();

        assertThat(KnowledgeBaseStatus.ACTIVE.canTransitionTo(KnowledgeBaseStatus.ACTIVE)).isFalse();
        assertThat(KnowledgeBaseStatus.ACTIVE.canTransitionTo(KnowledgeBaseStatus.DELETING)).isFalse();
        assertThat(KnowledgeBaseStatus.ACTIVE.canTransitionTo(KnowledgeBaseStatus.DELETED)).isFalse();
        assertThat(KnowledgeBaseStatus.DISABLED.canTransitionTo(KnowledgeBaseStatus.DISABLED)).isFalse();
        assertThat(KnowledgeBaseStatus.DISABLED.canTransitionTo(KnowledgeBaseStatus.DELETED)).isFalse();
        assertThat(KnowledgeBaseStatus.DELETING.canTransitionTo(KnowledgeBaseStatus.ACTIVE)).isFalse();
        assertThat(KnowledgeBaseStatus.DELETED.canTransitionTo(KnowledgeBaseStatus.ACTIVE)).isFalse();
    }

    @Test
    void toStringOmitsConfigurationAndAuditDetails() {
        KnowledgeBase knowledgeBase = base(b -> b.name("Docs"));
        assertThat(knowledgeBase.toString())
                .contains("slug=docs", "status=ACTIVE")
                .doesNotContain("text-embedding", "metadata", "createdBy");
    }

    private static KnowledgeBase base(Consumer<Builder> customize) {
        Builder builder = new Builder();
        customize.accept(builder);
        return builder.build();
    }

    @FunctionalInterface
    private interface Consumer<T> {
        void accept(T t);
    }

    private static final class Builder {
        UUID id = ID;
        TenantId tenantId = TENANT;
        String slug = "docs";
        String name = "Docs";
        String description;
        KnowledgeType knowledgeType = KnowledgeType.LOCAL;
        KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;
        UUID embeddingProviderId;
        String embeddingModel;
        UUID rerankProviderId;
        String rerankModel;
        ChunkPolicy chunkPolicy = ChunkPolicy.defaults();
        RetrievalConfig retrievalConfig = RetrievalConfig.defaults();
        JsonNode metadata = KnowledgeBase.emptyMetadata();
        long version = 0L;

        Builder embeddingProviderId(UUID v) { this.embeddingProviderId = v; return this; }
        Builder embeddingModel(String v) { this.embeddingModel = v; return this; }
        Builder rerankProviderId(UUID v) { this.rerankProviderId = v; return this; }
        Builder rerankModel(String v) { this.rerankModel = v; return this; }
        Builder slug(String v) { this.slug = v; return this; }
        Builder name(String v) { this.name = v; return this; }
        Builder description(String v) { this.description = v; return this; }
        Builder metadata(JsonNode v) { this.metadata = v; return this; }
        Builder version(long v) { this.version = v; return this; }

        KnowledgeBase build() {
            return new KnowledgeBase(id, tenantId, slug, name, description, knowledgeType, status,
                    embeddingProviderId, embeddingModel, rerankProviderId, rerankModel,
                    chunkPolicy, retrievalConfig, metadata, ACTOR, ACTOR, version,
                    Instant.EPOCH, Instant.EPOCH, null);
        }
    }
}
