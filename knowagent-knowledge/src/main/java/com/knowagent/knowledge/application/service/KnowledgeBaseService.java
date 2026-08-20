package com.knowagent.knowledge.application.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.model.provider.ModelProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service for knowledge-base CRUD.
 *
 * <p>Owns the business rules: slug normalization and tenant-scoped uniqueness,
 * embedding/rerank provider validation (tenant-owned, enabled, correct capability,
 * provider/model pairs), centralized status transitions, optimistic-lock conflict
 * handling and the delete guard that rejects knowledge bases still owning files. The
 * tenant id is always supplied by the caller from the authenticated principal.
 */
@Service
public class KnowledgeBaseService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_SUPPORTED_OFFSET = Integer.MAX_VALUE;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    private final KnowledgeBaseRepository repositories;
    private final KnowledgeFileReferenceChecker fileReferenceChecker;
    private final ModelProviderRepository providers;

    public KnowledgeBaseService(KnowledgeBaseRepository repositories,
                                KnowledgeFileReferenceChecker fileReferenceChecker,
                                ModelProviderRepository providers) {
        this.repositories = Objects.requireNonNull(repositories, "repositories must not be null");
        this.fileReferenceChecker =
                Objects.requireNonNull(fileReferenceChecker, "fileReferenceChecker must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
    }

    @Transactional
    public KnowledgeBase create(CreateKnowledgeBaseCommand command) {
        String slug = KnowledgeBase.normalizeSlug(command.slug());
        if (!KnowledgeBase.isValidSlug(slug)) {
            throw validation("slug must be lowercase and match [a-z0-9][a-z0-9_-]{0,98}");
        }
        String name = requireName(command.name());
        if (repositories.findActiveBySlug(command.tenantId(), slug).isPresent()) {
            throw conflict("A knowledge base with this slug already exists in the tenant.");
        }
        ResolvedPair embedding = resolvePair(command.tenantId(),
                command.embeddingProviderId(), command.embeddingModel(),
                null, null, ModelCapability.EMBEDDING, "embedding");
        ResolvedPair rerank = resolvePair(command.tenantId(),
                command.rerankProviderId(), command.rerankModel(),
                null, null, ModelCapability.RERANK, "rerank");
        ChunkPolicy chunkPolicy = command.chunkPolicy() != null ? command.chunkPolicy() : ChunkPolicy.defaults();
        RetrievalConfig retrievalConfig = command.retrievalConfig() != null
                ? command.retrievalConfig() : RetrievalConfig.defaults();
        String description = requireDescription(command.description());
        JsonNode metadata = command.metadata() != null
                ? requireMetadata(command.metadata()) : KnowledgeBase.emptyMetadata();

        Instant now = Instant.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                UUID.randomUUID(), command.tenantId(), slug, name, description,
                command.knowledgeType() != null ? command.knowledgeType() : KnowledgeType.LOCAL,
                KnowledgeBaseStatus.ACTIVE, embedding.providerId(), embedding.model(),
                rerank.providerId(), rerank.model(), chunkPolicy, retrievalConfig, metadata,
                command.actorId(), command.actorId(), 0L, now, now, null);
        try {
            repositories.save(knowledgeBase);
        } catch (DuplicateKeyException exception) {
            // The partial unique index is the race backstop: two concurrent creates
            // can both pass the pre-check, but only one commits.
            throw conflict("A knowledge base with this slug already exists in the tenant.");
        }
        return knowledgeBase;
    }

    @Transactional
    public KnowledgeBase update(UpdateKnowledgeBaseCommand command) {
        KnowledgeBase existing = repositories.findById(command.tenantId(), command.knowledgeBaseId())
                .orElseThrow(() -> notFound());

        String slug = existing.slug();
        if (command.slug() != null) {
            String newSlug = KnowledgeBase.normalizeSlug(command.slug());
            if (!KnowledgeBase.isValidSlug(newSlug)) {
                throw validation("slug must be lowercase and match [a-z0-9][a-z0-9_-]{0,98}");
            }
            if (!newSlug.equals(existing.slug())
                    && repositories.findActiveBySlug(command.tenantId(), newSlug).isPresent()) {
                throw conflict("A knowledge base with this slug already exists in the tenant.");
            }
            slug = newSlug;
        }

        String name = command.name() != null ? requireName(command.name()) : existing.name();
        KnowledgeBaseStatus status = existing.status();
        if (command.status() != null) {
            if (command.status() == KnowledgeBaseStatus.DELETED
                    || command.status() == KnowledgeBaseStatus.DELETING) {
                throw validation("status must be ACTIVE or DISABLED; use DELETE to remove the knowledge base");
            }
            if (!existing.status().canTransitionTo(command.status())) {
                throw validation("illegal status transition from " + existing.status()
                        + " to " + command.status());
            }
            status = command.status();
        }

        ResolvedPair embedding = resolvePair(command.tenantId(),
                command.embeddingProviderId(), command.embeddingModel(),
                existing.embeddingProviderId(), existing.embeddingModel(),
                ModelCapability.EMBEDDING, "embedding");
        ResolvedPair rerank = resolvePair(command.tenantId(),
                command.rerankProviderId(), command.rerankModel(),
                existing.rerankProviderId(), existing.rerankModel(),
                ModelCapability.RERANK, "rerank");

        ChunkPolicy chunkPolicy = command.chunkPolicy() != null
                ? command.chunkPolicy() : existing.chunkPolicy();
        RetrievalConfig retrievalConfig = command.retrievalConfig() != null
                ? command.retrievalConfig() : existing.retrievalConfig();
        String description = command.description() != null
                ? requireDescription(command.description()) : existing.description();
        JsonNode metadata = command.metadata() != null
                ? requireMetadata(command.metadata()) : existing.metadata();

        Instant now = Instant.now();
        KnowledgeBase updated = new KnowledgeBase(
                existing.id(), existing.tenantId(), slug, name, description,
                command.knowledgeType() != null ? command.knowledgeType() : existing.knowledgeType(),
                status, embedding.providerId(), embedding.model(),
                rerank.providerId(), rerank.model(), chunkPolicy, retrievalConfig, metadata,
                existing.createdBy(), command.actorId(), existing.version(),
                existing.createdAt(), now, null);

        try {
            if (repositories.updateConfig(updated) == 0) {
                throw conflict("The knowledge base was modified concurrently; please retry.");
            }
        } catch (DuplicateKeyException exception) {
            // Two concurrent PATCHes can both pass the slug pre-check; the partial unique
            // index on (tenant_id, slug) is the race backstop, exactly as in create.
            throw conflict("A knowledge base with this slug already exists in the tenant.");
        }
        return repositories.findById(command.tenantId(), command.knowledgeBaseId()).orElseThrow(() -> notFound());
    }

    @Transactional
    public void delete(TenantId tenantId, UUID knowledgeBaseId) {
        KnowledgeBase existing = repositories.findByIdForUpdate(tenantId, knowledgeBaseId)
                .orElseThrow(() -> notFound());
        if (fileReferenceChecker.hasActiveFiles(tenantId, knowledgeBaseId)) {
            throw conflict("The knowledge base still contains files; delete them first.");
        }
        if (repositories.softDelete(tenantId, knowledgeBaseId, existing.version()) == 0) {
            throw conflict("The knowledge base was modified concurrently; please retry.");
        }
    }

    public KnowledgeBase get(TenantId tenantId, UUID knowledgeBaseId) {
        return repositories.findById(tenantId, knowledgeBaseId).orElseThrow(() -> notFound());
    }

    public KnowledgeBasePage list(TenantId tenantId, String name, String slug,
                                  KnowledgeBaseStatus status, int page, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        validatePaging(page, size);
        return repositories.page(tenantId, buildLikePattern(name), buildLikePattern(slug),
                status, page, size);
    }

    private ResolvedPair resolvePair(TenantId tenantId, UUID submittedProvider, String submittedModel,
                                     UUID existingProvider, String existingModel,
                                     ModelCapability capability, String role) {
        UUID providerId = submittedProvider != null ? submittedProvider : existingProvider;
        String model = submittedModel != null ? submittedModel : existingModel;
        if (providerId == null && model == null) {
            return new ResolvedPair(null, null);
        }
        if (providerId == null || model == null || model.isBlank()) {
            throw validation(role + " provider and model must be configured together");
        }
        String trimmedModel = model.trim();
        // FOR KEY SHARE serializes against the provider's delete transaction (which takes
        // FOR UPDATE) and re-reads deleted_at IS NULL after the lock is granted: a provider
        // being deleted while this waits either blocks until the delete commits and then
        // surfaces as not-found (cross-tenant, deleted), or the write proceeds and the later
        // delete sees the new reference. Without the lock, "validate then insert" could bind
        // a provider that a concurrent delete just soft-removed - a stale-reference TOCTOU.
        ModelProvider provider = providers.findByIdForKeyShare(tenantId, providerId).orElseThrow(() -> notFound());
        if (!provider.enabled()) {
            throw validation(role + " provider must be enabled");
        }
        if (!provider.capabilities().contains(capability)) {
            throw validation(role + " provider must declare the " + capability.name() + " capability");
        }
        if (!provider.enabledModels().isEmpty()
                && provider.enabledModels().stream()
                .noneMatch(enabled -> enabled.name().equals(trimmedModel)
                        && enabled.capability() == capability)) {
            throw validation(role + " model '" + trimmedModel + "' is not enabled for "
                    + capability.name() + " on this provider");
        }
        return new ResolvedPair(providerId, trimmedModel);
    }

    private static void validatePaging(int page, int size) {
        if (page < 1) {
            throw validation("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw validation("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) (page - 1) * size;
        if (offset > MAX_SUPPORTED_OFFSET) {
            throw validation("page and size exceed the supported paging range");
        }
    }

    /**
     * Escapes {@code %}, {@code _} and {@code \} and wraps the keyword in {@code %...%}
     * for a case-insensitive contains match. A null or blank keyword yields {@code null},
     * which the query treats as "no filter".
     */
    static String buildLikePattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder pattern = new StringBuilder("%");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }

    private static String requireName(String value) {
        String name = value == null ? null : value.trim();
        if (name == null || name.isEmpty()) {
            throw validation("name must not be blank");
        }
        if (name.length() > 255) {
            throw validation("name must contain at most 255 characters");
        }
        return name;
    }

    private static String requireDescription(String value) {
        if (value != null && value.length() > MAX_DESCRIPTION_LENGTH) {
            throw validation("description must contain at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return value;
    }

    private static JsonNode requireMetadata(JsonNode metadata) {
        if (metadata != null && !metadata.isObject()) {
            throw validation("metadata must be a JSON object");
        }
        return metadata;
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist.");
    }

    private record ResolvedPair(UUID providerId, String model) {
    }
}
