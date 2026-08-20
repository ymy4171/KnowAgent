package com.knowagent.knowledge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.StringListJsonbTypeHandler;
import com.knowagent.knowledge.infrastructure.persistence.typehandler.StringMapJsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence row for {@code knowledge_chunks}. The UUID is generated in Java
 * ({@code IdType.INPUT}) and is reused as the Milvus entity id later, so it is never
 * regenerated on retry. The JSONB {@code section_path} and {@code metadata} columns use
 * structured type handlers; {@code content} is document text that must never leave the
 * persistence boundary in a response or log.
 */
@TableName(value = "knowledge_chunks", autoResultMap = true)
public class KnowledgeChunkPo {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private UUID knowledgeBaseId;
    private UUID fileId;
    private int chunkIndex;
    private String content;
    private String contentHash;
    private int tokenCount;
    private Integer startCharOffset;
    private Integer endCharOffset;
    private Integer startTokenOffset;
    private Integer endTokenOffset;
    private Integer pageNumber;
    @TableField(typeHandler = StringListJsonbTypeHandler.class)
    private List<String> sectionPath;
    @TableField(typeHandler = StringMapJsonbTypeHandler.class)
    private Map<String, String> metadata;
    private ChunkIndexStatus indexStatus;
    private String embeddingModelSpec;
    private String errorCode;
    private String errorMessage;
    @Version
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(UUID knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public UUID getFileId() { return fileId; }
    public void setFileId(UUID fileId) { this.fileId = fileId; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public Integer getStartCharOffset() { return startCharOffset; }
    public void setStartCharOffset(Integer startCharOffset) { this.startCharOffset = startCharOffset; }
    public Integer getEndCharOffset() { return endCharOffset; }
    public void setEndCharOffset(Integer endCharOffset) { this.endCharOffset = endCharOffset; }
    public Integer getStartTokenOffset() { return startTokenOffset; }
    public void setStartTokenOffset(Integer startTokenOffset) { this.startTokenOffset = startTokenOffset; }
    public Integer getEndTokenOffset() { return endTokenOffset; }
    public void setEndTokenOffset(Integer endTokenOffset) { this.endTokenOffset = endTokenOffset; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public List<String> getSectionPath() { return sectionPath; }
    public void setSectionPath(List<String> sectionPath) { this.sectionPath = sectionPath; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public ChunkIndexStatus getIndexStatus() { return indexStatus; }
    public void setIndexStatus(ChunkIndexStatus indexStatus) { this.indexStatus = indexStatus; }
    public String getEmbeddingModelSpec() { return embeddingModelSpec; }
    public void setEmbeddingModelSpec(String embeddingModelSpec) { this.embeddingModelSpec = embeddingModelSpec; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
