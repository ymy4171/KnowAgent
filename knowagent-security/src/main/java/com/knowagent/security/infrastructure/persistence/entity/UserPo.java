package com.knowagent.security.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.knowagent.security.domain.user.UserStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("users")
public class UserPo {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private UUID departmentId;
    private String loginName;
    private String displayName;
    private String email;
    private String phoneNumber;
    private String avatarObjectKey;
    private String passwordHash;
    private UserStatus status;
    private Integer loginFailedCount;
    private OffsetDateTime lastFailedLoginAt;
    private OffsetDateTime loginLockedUntil;
    private OffsetDateTime lastLoginAt;
    @Version
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }
    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getAvatarObjectKey() { return avatarObjectKey; }
    public void setAvatarObjectKey(String avatarObjectKey) { this.avatarObjectKey = avatarObjectKey; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public Integer getLoginFailedCount() { return loginFailedCount; }
    public void setLoginFailedCount(Integer loginFailedCount) { this.loginFailedCount = loginFailedCount; }
    public OffsetDateTime getLastFailedLoginAt() { return lastFailedLoginAt; }
    public void setLastFailedLoginAt(OffsetDateTime lastFailedLoginAt) { this.lastFailedLoginAt = lastFailedLoginAt; }
    public OffsetDateTime getLoginLockedUntil() { return loginLockedUntil; }
    public void setLoginLockedUntil(OffsetDateTime loginLockedUntil) { this.loginLockedUntil = loginLockedUntil; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
