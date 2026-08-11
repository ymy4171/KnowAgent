package com.knowagent.security.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.knowagent.security.domain.token.RefreshTokenStatus;
import com.knowagent.security.infrastructure.persistence.typehandler.PostgresInetTypeHandler;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName(value = "refresh_tokens", autoResultMap = true)
public class RefreshTokenPo {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private UUID userId;
    private UUID familyId;
    private UUID parentTokenId;
    private String tokenHash;
    private RefreshTokenStatus status;
    private OffsetDateTime issuedAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime consumedAt;
    private OffsetDateTime revokedAt;
    private String revokeReason;
    @TableField(typeHandler = PostgresInetTypeHandler.class)
    private InetAddress issuedIp;
    private String userAgent;
    @Version
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getFamilyId() { return familyId; }
    public void setFamilyId(UUID familyId) { this.familyId = familyId; }
    public UUID getParentTokenId() { return parentTokenId; }
    public void setParentTokenId(UUID parentTokenId) { this.parentTokenId = parentTokenId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public RefreshTokenStatus getStatus() { return status; }
    public void setStatus(RefreshTokenStatus status) { this.status = status; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime issuedAt) { this.issuedAt = issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public InetAddress getIssuedIp() { return issuedIp; }
    public void setIssuedIp(InetAddress issuedIp) { this.issuedIp = issuedIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
