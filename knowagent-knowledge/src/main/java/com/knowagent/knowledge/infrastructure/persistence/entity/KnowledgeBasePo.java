package com.knowagent.knowledge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.ChunkPolicyJsonbTypeHandler;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.RetrievalConfigJsonbTypeHandler;
import com.knowagent.knowledge.knowledgebase.KnowledgeBaseStatus;
import com.knowagent.knowledge.knowledgebase.KnowledgeType;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistence row for {@code knowledge_bases}. Enums map by name (matching the DB CHECK
 * values); the three JSONB columns use structured type handlers so no SQL is assembled
 * by hand. Ciphertext, provider secrets and internal headers never appear here.
 */
@TableName(value = "knowledge_bases", autoResultMap = true)
public class KnowledgeBasePo {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private String slug;
    private String name;
    private String description;
    private KnowledgeType knowledgeType;
    private KnowledgeBaseStatus status;
    private UUID embeddingProviderId;
    private String embeddingModel;
    private UUID rerankProviderId;
    private String rerankModel;
    @TableField(typeHandler = ChunkPolicyJsonbTypeHandler.class)
    private ChunkPolicy chunkPolicy;
    @TableField(typeHandler = RetrievalConfigJsonbTypeHandler.class)
    private RetrievalConfig retrievalConfig;
    @TableField(typeHandler = JsonNodeJsonbTypeHandler.class)
    private JsonNode metadata;
    private UUID createdBy;
    private UUID updatedBy;
    @Version
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public KnowledgeType getKnowledgeType() { return knowledgeType; }
    public void setKnowledgeType(KnowledgeType knowledgeType) { this.knowledgeType = knowledgeType; }
    public KnowledgeBaseStatus getStatus() { return status; }
    public void setStatus(KnowledgeBaseStatus status) { this.status = status; }
    public UUID getEmbeddingProviderId() { return embeddingProviderId; }
    public void setEmbeddingProviderId(UUID embeddingProviderId) { this.embeddingProviderId = embeddingProviderId; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public UUID getRerankProviderId() { return rerankProviderId; }
    public void setRerankProviderId(UUID rerankProviderId) { this.rerankProviderId = rerankProviderId; }
    public String getRerankModel() { return rerankModel; }
    public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }
    public ChunkPolicy getChunkPolicy() { return chunkPolicy; }
    public void setChunkPolicy(ChunkPolicy chunkPolicy) { this.chunkPolicy = chunkPolicy; }
    public RetrievalConfig getRetrievalConfig() { return retrievalConfig; }
    public void setRetrievalConfig(RetrievalConfig retrievalConfig) { this.retrievalConfig = retrievalConfig; }
    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode metadata) { this.metadata = metadata; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
