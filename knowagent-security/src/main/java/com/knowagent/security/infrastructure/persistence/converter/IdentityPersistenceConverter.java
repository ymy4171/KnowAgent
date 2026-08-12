package com.knowagent.security.infrastructure.persistence.converter;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.entity.RolePo;
import com.knowagent.security.infrastructure.persistence.entity.TenantPo;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import com.knowagent.security.infrastructure.persistence.entity.UserRolePo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

public final class IdentityPersistenceConverter {
    private IdentityPersistenceConverter() {
    }

    public static Tenant toDomain(TenantPo source) {
        try {
            return new Tenant(
                    TenantId.of(source.getId()), source.getSlug(), source.getName(), source.getStatus(),
                    source.getSettings(), requiredVersion(source.getVersion()), instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()), instant(source.getDeletedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow("tenant", exception);
        }
    }

    public static User toDomain(UserPo source) {
        try {
            return new User(
                    source.getId(), TenantId.of(source.getTenantId()), source.getDepartmentId(), source.getLoginName(),
                    source.getDisplayName(), source.getEmail(), source.getPhoneNumber(), source.getAvatarObjectKey(),
                    source.getPasswordHash(), source.getStatus(), requiredCount(source.getLoginFailedCount()),
                    instant(source.getLastFailedLoginAt()), instant(source.getLoginLockedUntil()),
                    instant(source.getLastLoginAt()), requiredVersion(source.getVersion()), instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()), instant(source.getDeletedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow("user", exception);
        }
    }

    public static Role toDomain(RolePo source) {
        try {
            return new Role(
                    source.getId(), TenantId.of(source.getTenantId()), source.getCode(), source.getName(),
                    source.getDescription(), Set.copyOf(source.getPermissions()), Boolean.TRUE.equals(source.getIsSystem()),
                    source.getStatus(), requiredVersion(source.getVersion()), instant(source.getCreatedAt()),
                    instant(source.getUpdatedAt()), instant(source.getDeletedAt()));
        } catch (RuntimeException exception) {
            throw invalidRow("role", exception);
        }
    }

    public static UserRole toDomain(UserRolePo source) {
        try {
            return new UserRole(
                    source.getId(), TenantId.of(source.getTenantId()), source.getUserId(), source.getRoleId(),
                    source.getGrantedBy(), instant(source.getGrantedAt()), instant(source.getExpiresAt()));
        } catch (RuntimeException exception) {
            throw invalidRow("user role", exception);
        }
    }

    public static TenantPo toPersistence(Tenant source) {
        try {
            TenantPo target = new TenantPo();
            target.setId(source.id().value());
            target.setSlug(source.slug());
            target.setName(source.name());
            target.setStatus(source.status());
            target.setSettings(source.settings());
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
            target.setDeletedAt(offsetDateTime(source.deletedAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow("tenant domain", exception);
        }
    }

    public static UserPo toPersistence(User source) {
        try {
            UserPo target = new UserPo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setDepartmentId(source.departmentId());
            target.setLoginName(source.loginName());
            target.setDisplayName(source.displayName());
            target.setEmail(source.email());
            target.setPhoneNumber(source.phoneNumber());
            target.setAvatarObjectKey(source.avatarObjectKey());
            target.setPasswordHash(source.passwordHash());
            target.setStatus(source.status());
            target.setLoginFailedCount(source.loginFailedCount());
            target.setLastFailedLoginAt(offsetDateTime(source.lastFailedLoginAt()));
            target.setLoginLockedUntil(offsetDateTime(source.loginLockedUntil()));
            target.setLastLoginAt(offsetDateTime(source.lastLoginAt()));
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
            target.setDeletedAt(offsetDateTime(source.deletedAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow("user domain", exception);
        }
    }

    public static RolePo toPersistence(Role source) {
        try {
            RolePo target = new RolePo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setCode(source.code());
            target.setName(source.name());
            target.setDescription(source.description());
            target.setPermissions(source.permissions());
            target.setIsSystem(source.system());
            target.setStatus(source.status());
            target.setVersion(source.version());
            target.setCreatedAt(offsetDateTime(source.createdAt()));
            target.setUpdatedAt(offsetDateTime(source.updatedAt()));
            target.setDeletedAt(offsetDateTime(source.deletedAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow("role domain", exception);
        }
    }

    public static UserRolePo toPersistence(UserRole source) {
        try {
            UserRolePo target = new UserRolePo();
            target.setId(source.id());
            target.setTenantId(source.tenantId().value());
            target.setUserId(source.userId());
            target.setRoleId(source.roleId());
            target.setGrantedBy(source.grantedBy());
            target.setGrantedAt(offsetDateTime(source.grantedAt()));
            target.setExpiresAt(offsetDateTime(source.expiresAt()));
            return target;
        } catch (RuntimeException exception) {
            throw invalidRow("user role domain", exception);
        }
    }

    public static RefreshToken toDomain(RefreshTokenPo source) {
        try {
            return new RefreshToken(
                    source.getId(), TenantId.of(source.getTenantId()), source.getUserId(), source.getFamilyId(),
                    source.getParentTokenId(), source.getTokenHash(), source.getStatus(), instant(source.getIssuedAt()),
                    instant(source.getExpiresAt()), instant(source.getConsumedAt()), instant(source.getRevokedAt()),
                    source.getRevokeReason(), source.getIssuedIp(), source.getUserAgent(),
                    requiredVersion(source.getVersion()));
        } catch (RuntimeException exception) {
            throw invalidRow("refresh token", exception);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    private static long requiredVersion(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        return value;
    }

    private static int requiredCount(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("count must not be null");
        }
        return value;
    }

    private static BusinessException invalidRow(String recordType, RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.INTERNAL_ERROR, "Invalid " + recordType + " persistence record");
        exception.initCause(cause);
        return exception;
    }
}
