package com.knowagent.api.knowledgebase;

import com.knowagent.api.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.knowagent.api.knowledgebase.dto.KnowledgeBasePageResponse;
import com.knowagent.api.knowledgebase.dto.KnowledgeBaseResponse;
import com.knowagent.api.knowledgebase.dto.UpdateKnowledgeBaseRequest;
import com.knowagent.knowledge.application.service.CreateKnowledgeBaseCommand;
import com.knowagent.knowledge.application.service.KnowledgeBaseService;
import com.knowagent.knowledge.application.service.UpdateKnowledgeBaseCommand;
import com.knowagent.knowledge.knowledgebase.KnowledgeBasePage;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.security.principal.TenantPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Knowledge-base management endpoints. The tenant id is always read from the
 * authenticated principal, never from the request body or a header; reads require
 * {@code KNOWLEDGE_BASE_READ} and mutations {@code KNOWLEDGE_BASE_WRITE}. Cross-tenant
 * ids surface as a 404.
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_BASE_WRITE')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseResponse create(@AuthenticationPrincipal TenantPrincipal principal,
                                        @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return KnowledgeBaseResponse.from(service.create(new CreateKnowledgeBaseCommand(
                principal.tenantId(), request.slug(), request.name(), request.description(),
                request.knowledgeType(), request.embeddingProviderId(), request.embeddingModel(),
                request.rerankProviderId(), request.rerankModel(),
                request.chunkPolicy() == null ? null : request.chunkPolicy().toDomain(),
                request.retrievalConfig() == null ? null : request.retrievalConfig().toDomain(),
                request.metadata(), principal.userId())));
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_BASE_READ')")
    @GetMapping
    public KnowledgeBasePageResponse list(@AuthenticationPrincipal TenantPrincipal principal,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String name,
                                          @RequestParam(required = false) String slug,
                                          @RequestParam(required = false) KnowledgeBaseStatus status) {
        KnowledgeBasePage result = service.list(principal.tenantId(), name, slug, status, page, size);
        List<KnowledgeBaseResponse> items = result.knowledgeBases().stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
        return new KnowledgeBasePageResponse(items, result.total(), page, size);
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_BASE_READ')")
    @GetMapping("/{id}")
    public KnowledgeBaseResponse get(@AuthenticationPrincipal TenantPrincipal principal,
                                     @PathVariable UUID id) {
        return KnowledgeBaseResponse.from(service.get(principal.tenantId(), id));
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_BASE_WRITE')")
    @PatchMapping("/{id}")
    public KnowledgeBaseResponse update(@AuthenticationPrincipal TenantPrincipal principal,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return KnowledgeBaseResponse.from(service.update(new UpdateKnowledgeBaseCommand(
                principal.tenantId(), id, request.slug(), request.name(), request.description(),
                request.status(), request.knowledgeType(), request.embeddingProviderId(), request.embeddingModel(),
                request.rerankProviderId(), request.rerankModel(),
                request.chunkPolicy() == null ? null : request.chunkPolicy().toDomain(),
                request.retrievalConfig() == null ? null : request.retrievalConfig().toDomain(),
                request.metadata(), principal.userId())));
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_BASE_WRITE')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        service.delete(principal.tenantId(), id);
    }
}
