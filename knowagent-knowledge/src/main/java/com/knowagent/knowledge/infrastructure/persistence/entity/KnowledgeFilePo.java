package com.knowagent.knowledge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.knowledge.file.KnowledgeFileStatus;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistence row for {@code knowledge_files}. Enums map by name (matching the DB CHECK
 * values); the JSONB columns use structured type handlers so no SQL is assembled by
 * hand. {@code objectKey} is a server-side storage address: it never leaves the
 * persistence boundary and must never be returned to a client.
 */
@TableName(value = "knowledge_files", autoResultMap = true)
public class KnowledgeFilePo {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private UUID knowledgeBaseId;
    private UUID parentFileId;
    private String uploadIdempotencyKey;
    private String displayName;
    private String originalFilename;
    private String objectKey;
    private String contentType;
    private String fileExtension;
    private String sha256;
    private long fileSizeBytes;
    private KnowledgeFileStatus status;
    private int chunkCount;
    private long tokenCount;
    @TableField(typeHandler = JsonNodeJsonbTypeHandler.class)
    private JsonNode processingParams;
    @TableField(typeHandler = JsonNodeJsonbTypeHandler.class)
    private JsonNode metadata;
    private String errorCode;
    private String errorMessage;
    private boolean retryable;
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
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(UUID knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public UUID getParentFileId() { return parentFileId; }
    public void setParentFileId(UUID parentFileId) { this.parentFileId = parentFileId; }
    public String getUploadIdempotencyKey() { return uploadIdempotencyKey; }
    public void setUploadIdempotencyKey(String uploadIdempotencyKey) { this.uploadIdempotencyKey = uploadIdempotencyKey; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public KnowledgeFileStatus getStatus() { return status; }
    public void setStatus(KnowledgeFileStatus status) { this.status = status; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public long getTokenCount() { return tokenCount; }
    public void setTokenCount(long tokenCount) { this.tokenCount = tokenCount; }
    public JsonNode getProcessingParams() { return processingParams; }
    public void setProcessingParams(JsonNode processingParams) { this.processingParams = processingParams; }
    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode metadata) { this.metadata = metadata; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
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
