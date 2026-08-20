package com.knowagent.api.knowledgebase;

import com.knowagent.api.knowledgebase.dto.KnowledgeRetrievalRequest;
import com.knowagent.api.knowledgebase.dto.KnowledgeRetrievalResponse;
import com.knowagent.knowledge.application.service.KnowledgeRetrievalCommand;
import com.knowagent.knowledge.application.service.KnowledgeRetrievalService;
import com.knowagent.security.principal.TenantPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP-only adapter for semantic retrieval; no Chat/RAG answer generation occurs here. */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/retrieval")
public class KnowledgeRetrievalController {

    private final KnowledgeRetrievalService service;

    public KnowledgeRetrievalController(KnowledgeRetrievalService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('KNOWLEDGE_RETRIEVE')")
    @PostMapping
    public KnowledgeRetrievalResponse retrieve(@AuthenticationPrincipal TenantPrincipal principal,
                                               @PathVariable UUID knowledgeBaseId,
                                               @Valid @RequestBody KnowledgeRetrievalRequest request) {
        return KnowledgeRetrievalResponse.from(service.retrieve(new KnowledgeRetrievalCommand(
                principal.tenantId(), knowledgeBaseId, request.query(), request.topK(),
                request.scoreThreshold(), request.fileIds())));
    }
}
