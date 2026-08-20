package com.knowagent.observability.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.observability.outbox.OutboxStatus;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName(value = "outbox_events", autoResultMap = true)
public class OutboxEventPo {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    @TableField(typeHandler = JsonNodeJsonbTypeHandler.class)
    private JsonNode payload;
    @TableField(typeHandler = JsonNodeJsonbTypeHandler.class)
    private JsonNode headers;
    private OutboxStatus status;
    private Integer retryCount;
    private Integer maxRetries;
    private OffsetDateTime nextRetryAt;
    private String lockedBy;
    private OffsetDateTime lockedUntil;
    private String lastError;
    private OffsetDateTime publishedAt;
    @Version
    private Long version;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
    public JsonNode getHeaders() { return headers; }
    public void setHeaders(JsonNode headers) { this.headers = headers; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(OffsetDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public OffsetDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(OffsetDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
