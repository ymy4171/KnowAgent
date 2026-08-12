package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.UserRolePo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRolePo> {

    /**
     * Bootstrap idempotency lookup: counts whether an <em>effective</em> user-to-role
     * assignment already exists inside one tenant. Expired bindings are intentionally
     * excluded because the permission query ({@link RoleMapper#selectEffectiveByUser})
     * also ignores them — an expired row would cause the bootstrap to skip binding
     * creation while the admin has no real permissions.
     *
     * <p>Runs before any authentication exists, so the tenant-line plugin is bypassed;
     * the SQL itself carries an explicit {@code tenant_id} condition as required by
     * the pre-authentication exception rules.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT COUNT(1)
            FROM user_roles
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND role_id = #{roleId}
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            """)
    long existsByTenantUserAndRole(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId);
}
