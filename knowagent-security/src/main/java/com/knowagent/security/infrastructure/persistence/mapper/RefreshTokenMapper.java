package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.typehandler.PostgresInetTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenPo> {
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, tenant_id, user_id, family_id, parent_token_id, token_hash, status,
                   issued_at, expires_at, consumed_at, revoked_at, revoke_reason, issued_ip,
                   user_agent, version
            FROM refresh_tokens
            WHERE token_hash = #{tokenHash}
            LIMIT 1
            """)
    @Results(id = "refreshTokenPoResultMap", value = {
            @Result(column = "issued_ip", property = "issuedIp", typeHandler = PostgresInetTypeHandler.class)
    })
    RefreshTokenPo selectByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Re-reads one token by primary key, explicitly scoped by tenant. Used after the
     * family-root lock so the status observed under the lock is authoritative even if
     * it changed since the hash lookup.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, tenant_id, user_id, family_id, parent_token_id, token_hash, status,
                   issued_at, expires_at, consumed_at, revoked_at, revoke_reason, issued_ip,
                   user_agent, version
            FROM refresh_tokens
            WHERE tenant_id = #{tenantId} AND id = #{tokenId}
            LIMIT 1
            """)
    @ResultMap("refreshTokenPoResultMap")
    RefreshTokenPo selectByIdAndTenant(@Param("tenantId") UUID tenantId, @Param("tokenId") UUID tokenId);

    /**
     * Locks one token family on its root row ({@code id = family_id}), serialising
     * every refresh, replay and logout of that family: a replay revocation can then
     * always see the whole family, including a just-issued successor, because no
     * concurrent rotation can insert one while the lock is held.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, tenant_id, user_id, family_id, parent_token_id, token_hash, status,
                   issued_at, expires_at, consumed_at, revoked_at, revoke_reason, issued_ip,
                   user_agent, version
            FROM refresh_tokens
            WHERE tenant_id = #{tenantId} AND id = #{familyId}
            LIMIT 1
            FOR UPDATE
            """)
    @ResultMap("refreshTokenPoResultMap")
    RefreshTokenPo selectFamilyRootForUpdate(@Param("tenantId") UUID tenantId, @Param("familyId") UUID familyId);

    /**
     * Compare-and-set consumption: only an ACTIVE row may become CONSUMED, so a
     * concurrent rotation that already consumed it makes this match zero rows.
     * {@code tenant_id} and {@code id} are both in the filter because no
     * {@code TenantContext} exists during rotation.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE refresh_tokens
            SET status = 'CONSUMED', consumed_at = #{consumedAt}, version = version + 1
            WHERE tenant_id = #{tenantId} AND id = #{tokenId} AND status = 'ACTIVE'
            """)
    int consumeActive(@Param("tenantId") UUID tenantId,
                      @Param("tokenId") UUID tokenId,
                      @Param("consumedAt") Instant consumedAt);

    /**
     * Revokes every still-ACTIVE token in one family. The stable reason is recorded
     * so a revoked family is auditable without ever storing a raw token.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE refresh_tokens
            SET status = 'REVOKED', revoked_at = #{revokedAt}, revoke_reason = #{reason},
                version = version + 1
            WHERE tenant_id = #{tenantId} AND family_id = #{familyId} AND status = 'ACTIVE'
            """)
    int revokeActiveFamily(@Param("tenantId") UUID tenantId,
                           @Param("familyId") UUID familyId,
                           @Param("revokedAt") Instant revokedAt,
                           @Param("reason") String reason);
}
