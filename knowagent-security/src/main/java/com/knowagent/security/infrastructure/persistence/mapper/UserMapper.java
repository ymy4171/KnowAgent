package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface UserMapper extends BaseMapper<UserPo> {
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, tenant_id, department_id, login_name, display_name, email, phone_number,
                   avatar_object_key, password_hash, status, login_failed_count, last_failed_login_at,
                   login_locked_until, last_login_at, version, created_at, updated_at, deleted_at
            FROM users
            WHERE tenant_id = #{tenantId}
              AND login_name = #{loginName}
              AND deleted_at IS NULL
            LIMIT 1
            """)
    UserPo selectByTenantAndLoginName(
            @Param("tenantId") UUID tenantId,
            @Param("loginName") String loginName);

    /**
     * Pre-authentication lookup used by the current-user endpoint. Bypasses the
     * tenant-line plugin, so the SQL must and does carry {@code tenant_id}
     * explicitly; the caller derives the tenant from the authenticated principal.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, tenant_id, department_id, login_name, display_name, email, phone_number,
                   avatar_object_key, password_hash, status, login_failed_count, last_failed_login_at,
                   login_locked_until, last_login_at, version, created_at, updated_at, deleted_at
            FROM users
            WHERE tenant_id = #{tenantId}
              AND id = #{userId}
              AND deleted_at IS NULL
            LIMIT 1
            """)
    UserPo selectByIdAndTenant(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId);

    /**
     * Persists login state (failed count, lock window, last login, status) under an
     * optimistic-lock guard. Runs during the login flow before any authentication
     * exists, so the tenant-line plugin is bypassed and the statement scopes itself
     * with an explicit {@code tenant_id} plus a {@code version = #{version}} check.
     * Returns the number of affected rows: 1 when the update applied, 0 when a
     * concurrent change moved the version first.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE users
            SET login_failed_count = #{loginFailedCount},
                last_failed_login_at = #{lastFailedLoginAt},
                login_locked_until = #{loginLockedUntil},
                last_login_at = #{lastLoginAt},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND deleted_at IS NULL
              AND version = #{version}
            """)
    int updateLoginState(UserPo record);

    /**
     * Atomically records one failed login attempt. The count increments in the
     * database itself ({@code login_failed_count = login_failed_count + 1}) so
     * concurrent attempts never lose a count, unlike a read-modify-write guarded by
     * the optimistic-lock version. Once the threshold is reached the row is marked
     * {@code LOCKED} with a temporary window ending at {@code lockUntil}; below the
     * threshold any (expired) window is cleared again. Runs during the login flow
     * before any authentication exists, so the tenant-line plugin is bypassed and
     * the statement scopes itself with an explicit {@code tenant_id}. Returns the
     * number of affected rows.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE users
            SET login_failed_count = login_failed_count + 1,
                last_failed_login_at = #{now},
                login_locked_until = CASE
                    WHEN login_failed_count + 1 >= #{maxFailedAttempts} THEN #{lockUntil}::timestamptz
                    ELSE NULL
                END,
                status = CASE
                    WHEN login_failed_count + 1 >= #{maxFailedAttempts} THEN 'LOCKED'
                    ELSE status
                END,
                version = version + 1,
                updated_at = #{now}
            WHERE tenant_id = #{tenantId}
              AND id = #{userId}
              AND deleted_at IS NULL
            """)
    int recordLoginFailure(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("maxFailedAttempts") int maxFailedAttempts,
            @Param("lockUntil") Instant lockUntil);

    /**
     * One page of users strictly inside one tenant, newest first. Runs in an
     * authenticated request where the tenant-line plugin is active, so in addition
     * to the explicit {@code tenant_id} condition the interceptor injects the
     * context tenant as a fail-closed backstop. The optional status and fuzzy
     * keyword filters use a static SQL shape ({@code COALESCE(#{...}, '') = ''})
     * so the tenant interceptor never has to rewrite dynamic SQL. The keyword must
     * arrive pre-escaped by the caller (see {@code UserQueryService}); {@code %} /
     * {@code _} / {@code \} are literal.
     */
    @Select("""
            SELECT id, tenant_id, department_id, login_name, display_name, email, phone_number,
                   avatar_object_key, password_hash, status, login_failed_count, last_failed_login_at,
                   login_locked_until, last_login_at, version, created_at, updated_at, deleted_at
            FROM users
            WHERE tenant_id = #{tenantId}
              AND deleted_at IS NULL
              AND (COALESCE(#{status}, '') = '' OR status = #{status})
              AND (COALESCE(#{keywordPattern}, '') = '' OR LOWER(login_name) LIKE LOWER(#{keywordPattern}) ESCAPE '\\'
                   OR LOWER(display_name) LIKE LOWER(#{keywordPattern}) ESCAPE '\\')
            ORDER BY created_at DESC, id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<UserPo> selectUserPage(
            @Param("tenantId") UUID tenantId,
            @Param("keywordPattern") String keywordPattern,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * Counts the same filtered result set as {@link #selectUserPage} so the page
     * total always agrees with the page contents.
     */
    @Select("""
            SELECT COUNT(*)
            FROM users
            WHERE tenant_id = #{tenantId}
              AND deleted_at IS NULL
              AND (COALESCE(#{status}, '') = '' OR status = #{status})
              AND (COALESCE(#{keywordPattern}, '') = '' OR LOWER(login_name) LIKE LOWER(#{keywordPattern}) ESCAPE '\\'
                   OR LOWER(display_name) LIKE LOWER(#{keywordPattern}) ESCAPE '\\')
            """)
    long countUsers(
            @Param("tenantId") UUID tenantId,
            @Param("keywordPattern") String keywordPattern,
            @Param("status") String status);
}
