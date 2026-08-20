package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.knowledge.application.port.out.KnowledgeBaseRepository;
import com.knowagent.knowledge.application.port.out.KnowledgeFileReferenceChecker;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.knowledgebase.KnowledgeBase;
import com.knowagent.knowledge.knowledgebase.KnowledgeBasePage;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseServiceTest {

    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID EMBEDDING_PROVIDER = UUID.randomUUID();
    private static final UUID RERANK_PROVIDER = UUID.randomUUID();

    private final FakeRepository repository = new FakeRepository();
    private final FakeFileReferenceChecker fileReferenceChecker = new FakeFileReferenceChecker();
    private final FakeProviderRepository providers = new FakeProviderRepository();
    private final KnowledgeBaseService service =
            new KnowledgeBaseService(repository, fileReferenceChecker, providers);

    @Test
    void createNormalizesSlugToLowercaseAndDefaultsConfigurations() {
        seedProvider(EMBEDDING_PROVIDER, Set.of(ModelCapability.EMBEDDING));
        seedProvider(RERANK_PROVIDER, Set.of(ModelCapability.RERANK));

        KnowledgeBase created = service.create(createCommand(b -> b.slug("  HR-Manual-2026 ")));

        assertThat(created.slug()).isEqualTo("hr-manual-2026");
        assertThat(created.knowledgeType()).isEqualTo(KnowledgeType.LOCAL);
        assertThat(created.status()).isEqualTo(KnowledgeBaseStatus.ACTIVE);
        assertThat(created.chunkPolicy()).isEqualTo(ChunkPolicy.defaults());
        assertThat(created.retrievalConfig()).isEqualTo(RetrievalConfig.defaults());
        assertThat(created.metadata().isObject()).isTrue();
        assertThat(created.metadata().isEmpty()).isTrue();
        assertThat(created.version()).isZero();
    }

    @Test
    void createHonoursExplicitConfigurations() {
        ChunkPolicy policy = new ChunkPolicy(ChunkPolicy.Strategy.MARKDOWN_HEADING, 500, 50);
        RetrievalConfig config = new RetrievalConfig(25, 0.7, true);
        JsonNode metadata = JsonNodeFactory.instance.objectNode().put("owner", "hr");

        KnowledgeBase created = service.create(createCommand(b -> {
            b.knowledgeType(KnowledgeType.EXTERNAL);
            b.chunkPolicy(policy);
            b.retrievalConfig(config);
            b.metadata(metadata);
        }));

        assertThat(created.knowledgeType()).isEqualTo(KnowledgeType.EXTERNAL);
        assertThat(created.chunkPolicy()).isEqualTo(policy);
        assertThat(created.retrievalConfig()).isEqualTo(config);
        assertThat(created.metadata().get("owner").asText()).isEqualTo("hr");
    }

    @Test
    void createRejectsInvalidSlugAndBlankOrTooLongName() {
        // Uppercase slugs are normalized, not rejected - use shapes that stay invalid
        // after normalization (whitespace, a leading separator, an empty slug).
        assertValidationError(() -> service.create(createCommand(b -> b.slug("Has Space"))));
        assertValidationError(() -> service.create(createCommand(b -> b.slug("-leading"))));
        assertValidationError(() -> service.create(createCommand(b -> b.name("  "))));
        assertValidationError(() -> service.create(createCommand(b -> b.name("x".repeat(256)))));
    }

    @Test
    void createRejectsOverlongDescriptionAndNonObjectMetadata() {
        assertValidationError(() -> service.create(
                createCommand(b -> b.description("x".repeat(10_001)))));
        assertValidationError(() -> service.create(
                createCommand(b -> b.metadata(JsonNodeFactory.instance.arrayNode()))));
    }

    @Test
    void updateRejectsOverlongDescriptionAndNonObjectMetadata() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));

        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, "x".repeat(10_001), null, null, null, null, null, null,
                null, null, null, ACTOR)));
        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, null, null, null, null, null, null, null, null,
                JsonNodeFactory.instance.arrayNode(), ACTOR)));
    }

    @Test
    void createRejectsHalfConfiguredProviderModelPair() {
        seedProvider(EMBEDDING_PROVIDER, Set.of(ModelCapability.EMBEDDING));

        // Provider id without a model.
        assertValidationError(() -> service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel(null);
        })));
        // Model without a provider id.
        assertValidationError(() -> service.create(createCommand(b -> b.embeddingModel("text-embedding-3"))));
    }

    @Test
    void createRejectsDisabledProviderAndMissingCapability() {
        seedProvider(EMBEDDING_PROVIDER, TENANT_A, Set.of(ModelCapability.CHAT), false);
        assertValidationError(() -> service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
        })));

        seedProvider(RERANK_PROVIDER, Set.of(ModelCapability.RERANK));
        // The embedding provider only declares CHAT: binding it for embedding fails.
        seedProvider(EMBEDDING_PROVIDER, Set.of(ModelCapability.CHAT));
        assertValidationError(() -> service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
        })));
    }

    @Test
    void createCrossTenantProviderIsANonRevealingNotFound() {
        seedProvider(EMBEDDING_PROVIDER, TENANT_B, Set.of(ModelCapability.EMBEDDING));

        assertThatThrownBy(() -> service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
        })))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void createDuplicateSlugReturnsConflict() {
        service.create(createCommand(b -> b.slug("docs")));

        assertThatThrownBy(() -> service.create(createCommand(b -> b.slug("DOCS"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void createDuplicateSlugRaceReturnsConflict() {
        service.create(createCommand(b -> b.slug("docs")));
        repository.failNextSaveWithDuplicate = true;

        assertThatThrownBy(() -> service.create(createCommand(b -> b.slug("docs"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void createRejectsModelNotListedInEnabledModels() {
        seedProvider(EMBEDDING_PROVIDER, TENANT_A, Set.of(ModelCapability.EMBEDDING), true,
                List.of(new EnabledModel("text-embedding-3", ModelCapability.EMBEDDING)));

        assertValidationError(() -> service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("some-other-embedding");
        })));
    }

    @Test
    void createRejectsModelWhoseEnabledCapabilityDoesNotMatchTheRole() {
        // The provider declares EMBEDDING but only registers the model for CHAT, so the
        // model name alone is not enough - the capability of the enabled entry must match.
        seedProvider(EMBEDDING_PROVIDER, TENANT_A, Set.of(ModelCapability.EMBEDDING, ModelCapability.CHAT),
                true, List.of(new EnabledModel("text-embedding-3", ModelCapability.CHAT)));

        assertValidationError(() -> service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
        })));
    }

    @Test
    void createAcceptsModelListedInEnabledModelsWithMatchingCapability() {
        seedProvider(EMBEDDING_PROVIDER, TENANT_A, Set.of(ModelCapability.EMBEDDING), true,
                List.of(new EnabledModel("text-embedding-3", ModelCapability.EMBEDDING)));
        seedProvider(RERANK_PROVIDER, TENANT_A, Set.of(ModelCapability.RERANK), true,
                List.of(new EnabledModel("bge-reranker-v2", ModelCapability.RERANK)));

        KnowledgeBase created = service.create(createCommand(b -> {
            b.slug("bound-kb");
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
            b.rerankProviderId(RERANK_PROVIDER);
            b.rerankModel("bge-reranker-v2");
        }));

        assertThat(created.embeddingModel()).isEqualTo("text-embedding-3");
        assertThat(created.rerankModel()).isEqualTo("bge-reranker-v2");
    }

    @Test
    void createAcceptsAnyModelWhenEnabledModelsIsEmpty() {
        // An empty enabled_models catalog means the tenant has not constrained the model
        // list, so any model name is accepted as long as the capability is declared.
        seedProvider(EMBEDDING_PROVIDER, Set.of(ModelCapability.EMBEDDING));

        KnowledgeBase created = service.create(createCommand(b -> {
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("arbitrary-but-declared-capability");
        }));

        assertThat(created.embeddingModel()).isEqualTo("arbitrary-but-declared-capability");
    }

    @Test
    void updateRenamesAndChangesSlug() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));

        KnowledgeBase updated = service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), "new-docs", "New Docs", null, null, null, null, null, null, null,
                null, null, null, ACTOR));

        assertThat(updated.slug()).isEqualTo("new-docs");
        assertThat(updated.name()).isEqualTo("New Docs");
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.version()).isEqualTo(created.version() + 1);
    }

    @Test
    void updateKeepsStoredFieldsWhenNotSubmitted() {
        seedProvider(EMBEDDING_PROVIDER, Set.of(ModelCapability.EMBEDDING));
        KnowledgeBase created = service.create(createCommand(b -> {
            b.slug("docs");
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
        }));

        KnowledgeBase updated = service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, "Renamed only", null, null, null, null, null, null, null,
                null, null, null, ACTOR));

        assertThat(updated.name()).isEqualTo("Renamed only");
        assertThat(updated.slug()).isEqualTo("docs");
        assertThat(updated.embeddingProviderId()).isEqualTo(EMBEDDING_PROVIDER);
        assertThat(updated.embeddingModel()).isEqualTo("text-embedding-3");
        assertThat(updated.description()).isNull();
    }

    @Test
    void updateValidatesNewSlugAndRejectsConflictingSlug() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));
        service.create(createCommand(b -> b.slug("taken")));

        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), "Bad Slug", null, null, null, null, null, null, null, null,
                null, null, null, ACTOR)));
        assertThatThrownBy(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), "taken", null, null, null, null, null, null, null, null,
                null, null, null, ACTOR)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void updateDuplicateSlugRaceReturnsConflict() {
        // Two concurrent PATCHes can both pass the slug pre-check; the partial unique
        // index backstop must surface as a stable CONFLICT, not a 500.
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));
        repository.failNextConfigUpdateWithDuplicate = true;

        assertThatThrownBy(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), "taken", null, null, null, null, null, null, null,
                null, null, null, null, ACTOR)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        // The failed unique-key write changed nothing.
        assertThat(repository.byId.get(created.id()).slug()).isEqualTo("docs");
    }

    @Test
    void updateRejectsModelNotListedInEnabledModelsWhenRebinding() {
        seedProvider(EMBEDDING_PROVIDER, TENANT_A, Set.of(ModelCapability.EMBEDDING), true,
                List.of(new EnabledModel("text-embedding-3", ModelCapability.EMBEDDING)));
        KnowledgeBase created = service.create(createCommand(b -> {
            b.slug("docs");
            b.embeddingProviderId(EMBEDDING_PROVIDER);
            b.embeddingModel("text-embedding-3");
        }));

        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, null, null, EMBEDDING_PROVIDER,
                "not-in-the-catalog", null, null, null, null, null, ACTOR)));
    }

    @Test
    void updateAllowsDisableAndReEnableButRejectsDeletionTargetsAndNoOpTransitions() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));

        KnowledgeBase disabled = service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, KnowledgeBaseStatus.DISABLED, null, null, null,
                null, null, null, null, null, ACTOR));
        assertThat(disabled.status()).isEqualTo(KnowledgeBaseStatus.DISABLED);

        KnowledgeBase reEnabled = service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, KnowledgeBaseStatus.ACTIVE, null, null, null,
                null, null, null, null, null, ACTOR));
        assertThat(reEnabled.status()).isEqualTo(KnowledgeBaseStatus.ACTIVE);

        // Same-status and deletion targets are illegal; DELETE is the only way to DELETED.
        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, KnowledgeBaseStatus.ACTIVE, null, null, null,
                null, null, null, null, null, ACTOR)));
        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, KnowledgeBaseStatus.DELETED, null, null, null,
                null, null, null, null, null, ACTOR)));
        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, KnowledgeBaseStatus.DELETING, null, null, null,
                null, null, null, null, null, ACTOR)));
    }

    @Test
    void updateHalfConfiguredRebindingIsRejected() {
        // A knowledge base without an embedding binding; submitting only a provider id
        // leaves the model side unresolved.
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));
        seedProvider(EMBEDDING_PROVIDER, Set.of(ModelCapability.EMBEDDING));

        assertValidationError(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, null, null, null, null, EMBEDDING_PROVIDER, null,
                null, null, null, null, null, ACTOR)));
    }

    @Test
    void updateVersionGuardRejectingZeroRowsIsAConflictAndNothingIsWritten() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));
        repository.failNextConfigUpdateWithConflict = true;

        assertThatThrownBy(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, created.id(), null, "stale write", null, null, null, null, null, null, null,
                null, null, null, ACTOR)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        // The failed optimistic update wrote nothing.
        assertThat(repository.byId.get(created.id()).name()).isEqualTo("Default knowledge base");
        assertThat(repository.byId.get(created.id()).version()).isZero();
    }

    @Test
    void updateAndGetReturnNotFoundForUnknownOrCrossTenantKnowledgeBase() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));

        assertNotFound(() -> service.update(new UpdateKnowledgeBaseCommand(
                TENANT_B, created.id(), null, "nope", null, null, null, null, null, null, null,
                null, null, null, ACTOR)));
        assertNotFound(() -> service.get(TENANT_B, created.id()));
        assertNotFound(() -> service.get(TENANT_A, UUID.randomUUID()));
    }

    @Test
    void deleteRejectsKnowledgeBaseStillOwningFiles() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));
        fileReferenceChecker.active.add(created.id());

        assertThatThrownBy(() -> service.delete(TENANT_A, created.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        // Nothing was removed.
        assertThat(repository.byId.containsKey(created.id())).isTrue();
    }

    @Test
    void deleteSoftDeletesAndAllowsSlugReuse() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("reusable")));

        service.delete(TENANT_A, created.id());

        assertNotFound(() -> service.get(TENANT_A, created.id()));
        KnowledgeBase recreated = service.create(createCommand(b -> b.slug("reusable")));
        assertThat(recreated.id()).isNotEqualTo(created.id());
    }

    @Test
    void deleteVersionGuardRejectingZeroRowsIsAConflict() {
        KnowledgeBase created = service.create(createCommand(b -> b.slug("docs")));
        repository.failNextSoftDeleteWithConflict = true;

        assertThatThrownBy(() -> service.delete(TENANT_A, created.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(repository.byId.containsKey(created.id())).isTrue();
    }

    @Test
    void deleteReturnsNotFoundForUnknownKnowledgeBase() {
        assertNotFound(() -> service.delete(TENANT_A, UUID.randomUUID()));
    }

    @Test
    void listIsTenantScopedAndAppliesFilters() {
        KnowledgeBase docs = service.create(createCommand(b -> {
            b.slug("docs");
            b.name("Developer docs");
        }));
        KnowledgeBase hr = service.create(createCommand(b -> {
            b.slug("hr-manual");
            b.name("HR Manual");
        }));
        KnowledgeBase disabled = service.create(createCommand(b -> b.slug("archive")));
        service.update(new UpdateKnowledgeBaseCommand(
                TENANT_A, disabled.id(), null, null, null, KnowledgeBaseStatus.DISABLED, null, null, null,
                null, null, null, null, null, ACTOR));

        assertThat(service.list(TENANT_B, null, null, null, 1, 20).total()).isZero();
        assertThat(service.list(TENANT_A, null, null, null, 1, 20).total()).isEqualTo(3);

        KnowledgeBasePage byName = service.list(TENANT_A, "manual", null, null, 1, 20);
        assertThat(byName.knowledgeBases()).extracting(KnowledgeBase::slug).containsExactly("hr-manual");

        KnowledgeBasePage bySlug = service.list(TENANT_A, null, "doc", null, 1, 20);
        assertThat(bySlug.knowledgeBases()).extracting(KnowledgeBase::slug).containsExactly("docs");

        KnowledgeBasePage activeOnly = service.list(TENANT_A, null, null, KnowledgeBaseStatus.ACTIVE, 1, 20);
        assertThat(activeOnly.knowledgeBases()).extracting(KnowledgeBase::slug)
                .containsExactlyInAnyOrder("docs", "hr-manual");
    }

    @Test
    void listPassesTheNormalizedPatternAndPagingToTheRepository() {
        repository.recordLastCall = true;
        service.list(TENANT_A, "  a%b_c\\d ", "SLUG", KnowledgeBaseStatus.ACTIVE, 2, 5);

        assertThat(repository.lastNamePattern).isEqualTo("%a\\%b\\_c\\\\d%");
        assertThat(repository.lastSlugPattern).isEqualTo("%SLUG%");
        assertThat(repository.lastStatus).isEqualTo(KnowledgeBaseStatus.ACTIVE);
        assertThat(repository.lastPage).isEqualTo(2);
        assertThat(repository.lastSize).isEqualTo(5);
    }

    @Test
    void invalidPagingIsRejected() {
        assertValidationError(() -> service.list(TENANT_A, null, null, null, 0, 20));
        assertValidationError(() -> service.list(TENANT_A, null, null, null, 1, 0));
        assertValidationError(() -> service.list(TENANT_A, null, null, null, 1, 101));
    }

    @Test
    void buildLikePatternEscapesMetacharacters() {
        assertThat(KnowledgeBaseService.buildLikePattern(null)).isNull();
        assertThat(KnowledgeBaseService.buildLikePattern("   ")).isNull();
        assertThat(KnowledgeBaseService.buildLikePattern("alice")).isEqualTo("%alice%");
        assertThat(KnowledgeBaseService.buildLikePattern("a%b_c\\d")).isEqualTo("%a\\%b\\_c\\\\d%");
        assertThat(KnowledgeBaseService.buildLikePattern("张伟")).isEqualTo("%张伟%");
    }

    private void seedProvider(UUID id, Set<ModelCapability> capabilities) {
        seedProvider(id, TENANT_A, capabilities);
    }

    private void seedProvider(UUID id, TenantId tenantId, Set<ModelCapability> capabilities) {
        seedProvider(id, tenantId, capabilities, true);
    }

    private void seedProvider(UUID id, TenantId tenantId, Set<ModelCapability> capabilities, boolean enabled) {
        seedProvider(id, tenantId, capabilities, enabled, List.of());
    }

    private void seedProvider(UUID id, TenantId tenantId, Set<ModelCapability> capabilities,
                              boolean enabled, List<EnabledModel> enabledModels) {
        providers.byId.put(id, new ModelProvider(
                id, tenantId, "provider-" + id.toString().substring(0, 8), "Provider",
                AdapterType.OPENAI_COMPATIBLE, "https://api.example.com/v1", null, null, null, null,
                capabilities, enabledModels, JsonNodeFactory.instance.objectNode(), enabled,
                HealthStatus.UNKNOWN, 1L, ACTOR, ACTOR, 0L, Instant.EPOCH, Instant.EPOCH, null));
    }

    private static CreateKnowledgeBaseCommand createCommand(Consumer<CommandBuilder> customize) {
        CommandBuilder builder = new CommandBuilder();
        customize.accept(builder);
        return builder.build();
    }

    private void assertValidationError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static final class CommandBuilder {
        String slug = "default-kb";
        String name = "Default knowledge base";
        String description;
        KnowledgeType knowledgeType;
        UUID embeddingProviderId;
        String embeddingModel;
        UUID rerankProviderId;
        String rerankModel;
        ChunkPolicy chunkPolicy;
        RetrievalConfig retrievalConfig;
        JsonNode metadata;

        CommandBuilder slug(String v) { this.slug = v; return this; }
        CommandBuilder name(String v) { this.name = v; return this; }
        CommandBuilder description(String v) { this.description = v; return this; }
        CommandBuilder knowledgeType(KnowledgeType v) { this.knowledgeType = v; return this; }
        CommandBuilder embeddingProviderId(UUID v) { this.embeddingProviderId = v; return this; }
        CommandBuilder embeddingModel(String v) { this.embeddingModel = v; return this; }
        CommandBuilder rerankProviderId(UUID v) { this.rerankProviderId = v; return this; }
        CommandBuilder rerankModel(String v) { this.rerankModel = v; return this; }
        CommandBuilder chunkPolicy(ChunkPolicy v) { this.chunkPolicy = v; return this; }
        CommandBuilder retrievalConfig(RetrievalConfig v) { this.retrievalConfig = v; return this; }
        CommandBuilder metadata(JsonNode v) { this.metadata = v; return this; }

        CreateKnowledgeBaseCommand build() {
            return new CreateKnowledgeBaseCommand(
                    TENANT_A, slug, name, description, knowledgeType, embeddingProviderId, embeddingModel,
                    rerankProviderId, rerankModel, chunkPolicy, retrievalConfig, metadata, ACTOR);
        }
    }

    private static final class FakeRepository implements KnowledgeBaseRepository {
        final Map<UUID, KnowledgeBase> byId = new HashMap<>();
        final Set<UUID> deleted = new HashSet<>();
        boolean failNextSaveWithDuplicate;
        boolean failNextConfigUpdateWithDuplicate;
        boolean failNextConfigUpdateWithConflict;
        boolean failNextSoftDeleteWithConflict;
        boolean recordLastCall;
        String lastNamePattern;
        String lastSlugPattern;
        KnowledgeBaseStatus lastStatus;
        int lastPage;
        int lastSize;

        @Override
        public void save(KnowledgeBase knowledgeBase) {
            if (failNextSaveWithDuplicate) {
                failNextSaveWithDuplicate = false;
                throw new DuplicateKeyException("simulated slug unique-key race");
            }
            byId.put(knowledgeBase.id(), knowledgeBase);
        }

        @Override
        public Optional<KnowledgeBase> findById(TenantId tenantId, UUID id) {
            KnowledgeBase kb = byId.get(id);
            return kb != null && kb.tenantId().equals(tenantId) && !deleted.contains(id)
                    ? Optional.of(kb) : Optional.empty();
        }

        @Override
        public Optional<KnowledgeBase> findByIdForUpdate(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<KnowledgeBase> findByIdForKeyShare(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<KnowledgeBase> findActiveBySlug(TenantId tenantId, String slug) {
            return byId.values().stream()
                    .filter(kb -> kb.tenantId().equals(tenantId) && kb.slug().equals(slug)
                            && !deleted.contains(kb.id()))
                    .findFirst();
        }

        @Override
        public KnowledgeBasePage page(TenantId tenantId, String namePattern, String slugPattern,
                                      KnowledgeBaseStatus status, int page, int size) {
            if (recordLastCall) {
                lastNamePattern = namePattern;
                lastSlugPattern = slugPattern;
                lastStatus = status;
                lastPage = page;
                lastSize = size;
            }
            List<KnowledgeBase> all = byId.values().stream()
                    .filter(kb -> kb.tenantId().equals(tenantId) && !deleted.contains(kb.id()))
                    .filter(kb -> status == null || kb.status() == status)
                    .filter(kb -> matches(namePattern, kb.name()))
                    .filter(kb -> matches(slugPattern, kb.slug()))
                    .sorted(Comparator.comparing(KnowledgeBase::createdAt).reversed()
                            .thenComparing(KnowledgeBase::id))
                    .toList();
            int offset = (page - 1) * size;
            int end = Math.min(offset + size, all.size());
            List<KnowledgeBase> slice = offset >= all.size() ? List.of() : all.subList(offset, end);
            return new KnowledgeBasePage(slice, all.size());
        }

        @Override
        public int updateConfig(KnowledgeBase knowledgeBase) {
            if (failNextConfigUpdateWithDuplicate) {
                failNextConfigUpdateWithDuplicate = false;
                throw new DuplicateKeyException("simulated slug unique-key race");
            }
            if (failNextConfigUpdateWithConflict) {
                failNextConfigUpdateWithConflict = false;
                return 0;
            }
            KnowledgeBase existing = byId.get(knowledgeBase.id());
            if (existing == null || existing.version() != knowledgeBase.version()
                    || deleted.contains(knowledgeBase.id())) {
                return 0;
            }
            byId.put(knowledgeBase.id(), bumped(knowledgeBase));
            return 1;
        }

        @Override
        public int softDelete(TenantId tenantId, UUID id, long version) {
            if (failNextSoftDeleteWithConflict) {
                failNextSoftDeleteWithConflict = false;
                return 0;
            }
            KnowledgeBase existing = byId.get(id);
            if (existing == null || existing.version() != version || deleted.contains(id)) {
                return 0;
            }
            deleted.add(id);
            return 1;
        }

        private static KnowledgeBase bumped(KnowledgeBase kb) {
            return new KnowledgeBase(
                    kb.id(), kb.tenantId(), kb.slug(), kb.name(), kb.description(), kb.knowledgeType(),
                    kb.status(), kb.embeddingProviderId(), kb.embeddingModel(), kb.rerankProviderId(),
                    kb.rerankModel(), kb.chunkPolicy(), kb.retrievalConfig(), kb.metadata(),
                    kb.createdBy(), kb.updatedBy(), kb.version() + 1, kb.createdAt(),
                    Instant.now(), kb.deletedAt());
        }

        private static boolean matches(String pattern, String value) {
            if (pattern == null || value == null) {
                return true;
            }
            String core = pattern.substring(1, pattern.length() - 1);
            String unescaped = core.replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\");
            return value.toLowerCase().contains(unescaped.toLowerCase());
        }
    }

    private static final class FakeFileReferenceChecker implements KnowledgeFileReferenceChecker {
        final Set<UUID> active = new HashSet<>();

        @Override
        public boolean hasActiveFiles(TenantId tenantId, UUID knowledgeBaseId) {
            return active.contains(knowledgeBaseId);
        }
    }

    private static final class FakeProviderRepository implements ModelProviderRepository {
        final Map<UUID, ModelProvider> byId = new HashMap<>();

        @Override
        public Optional<ModelProvider> findById(TenantId tenantId, UUID id) {
            ModelProvider provider = byId.get(id);
            return provider != null && provider.tenantId().equals(tenantId)
                    ? Optional.of(provider) : Optional.empty();
        }

        @Override
        public void save(ModelProvider provider) {
            throw new UnsupportedOperationException("not exercised by KnowledgeBaseService");
        }

        @Override
        public Optional<ModelProvider> findByIdForUpdate(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<ModelProvider> findByIdForKeyShare(TenantId tenantId, UUID id) {
            return findById(tenantId, id);
        }

        @Override
        public Optional<ModelProvider> findActiveByKey(TenantId tenantId, String providerKey) {
            throw new UnsupportedOperationException("not exercised by KnowledgeBaseService");
        }

        @Override
        public com.knowagent.model.provider.ModelProviderPage page(TenantId tenantId, int page, int size) {
            throw new UnsupportedOperationException("not exercised by KnowledgeBaseService");
        }

        @Override
        public int updateConfig(ModelProvider provider) {
            throw new UnsupportedOperationException("not exercised by KnowledgeBaseService");
        }

        @Override
        public int softDelete(TenantId tenantId, UUID id, long version) {
            throw new UnsupportedOperationException("not exercised by KnowledgeBaseService");
        }
    }
}
