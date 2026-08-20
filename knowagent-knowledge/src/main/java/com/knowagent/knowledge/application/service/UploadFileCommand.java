package com.knowagent.knowledge.application.service;

import com.knowagent.common.tenant.TenantId;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * A single knowledge-file upload. The raw multipart stream is handed over here, never
 * to a controller: the service spools it to a bounded temp file, hashes it and decides
 * the document type from content before anything touches object storage. There is
 * deliberately no declared {@code Content-Type} here - content sniffing must not trust
 * the client header. {@code tenantId} and {@code actorId} come from the authenticated
 * principal; {@code idempotencyKey} is the optional client {@code Idempotency-Key}.
 */
public record UploadFileCommand(
        TenantId tenantId,
        UUID knowledgeBaseId,
        UUID actorId,
        String idempotencyKey,
        String originalFilename,
        InputStream content
) {

    public UploadFileCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
