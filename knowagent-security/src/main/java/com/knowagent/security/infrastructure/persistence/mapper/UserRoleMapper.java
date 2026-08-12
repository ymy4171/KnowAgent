package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.UserRolePo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRolePo> {

    /**
     * Atomically inserts a missing bootstrap assignment or reactivates an expired
     * assignment. The table has a unique constraint on tenant/user/role, so a
     * check-then-insert flow cannot recreate an expired row and is also racy under
     * concurrent startup. An already-effective assignment is left unchanged.
     *
     * <p>Runs before authentication exists. The tenant-line plugin is bypassed, while
     * the statement carries {@code tenant_id} explicitly.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Insert("""
            INSERT INTO user_roles (
                id, tenant_id, user_id, role_id, granted_by, granted_at, expires_at
            ) VALUES (
                #{id}, #{tenantId}, #{userId}, #{roleId}, #{grantedBy}, #{grantedAt}, NULL
            )
            ON CONFLICT ON CONSTRAINT uq_user_roles_assignment
            DO UPDATE SET
                granted_by = EXCLUDED.granted_by,
                granted_at = EXCLUDED.granted_at,
                expires_at = NULL
            WHERE user_roles.expires_at IS NOT NULL
              AND user_roles.expires_at <= CURRENT_TIMESTAMP
            """)
    int ensureEffectiveAssignment(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("grantedBy") UUID grantedBy,
            @Param("grantedAt") OffsetDateTime grantedAt);
}
