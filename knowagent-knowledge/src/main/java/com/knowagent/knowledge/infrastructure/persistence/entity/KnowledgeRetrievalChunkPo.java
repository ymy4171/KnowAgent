package com.knowagent.knowledge.infrastructure.persistence.entity;

import com.knowagent.knowledge.chunk.ChunkIndexStatus;
import com.knowagent.knowledge.file.KnowledgeFileStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Narrow persistence projection for retrieval hydration; not a domain or HTTP type. */
public final class KnowledgeRetrievalChunkPo {

    private UUID chunkId;
    private UUID tenantId;
    private UUID knowledgeBaseId;
    private UUID fileId;
    private String displayName;
    private String content;
    private Integer pageNumber;
    private List<String> sectionPath;
    private ChunkIndexStatus indexStatus;
    private KnowledgeFileStatus fileStatus;
    private OffsetDateTime fileDeletedAt;

    public UUID getChunkId() { return chunkId; }
    public void setChunkId(UUID chunkId) { this.chunkId = chunkId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(UUID knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public UUID getFileId() { return fileId; }
    public void setFileId(UUID fileId) { this.fileId = fileId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public List<String> getSectionPath() { return sectionPath; }
    public void setSectionPath(List<String> sectionPath) { this.sectionPath = sectionPath; }
    public ChunkIndexStatus getIndexStatus() { return indexStatus; }
    public void setIndexStatus(ChunkIndexStatus indexStatus) { this.indexStatus = indexStatus; }
    public KnowledgeFileStatus getFileStatus() { return fileStatus; }
    public void setFileStatus(KnowledgeFileStatus fileStatus) { this.fileStatus = fileStatus; }
    public OffsetDateTime getFileDeletedAt() { return fileDeletedAt; }
    public void setFileDeletedAt(OffsetDateTime fileDeletedAt) { this.fileDeletedAt = fileDeletedAt; }
}
