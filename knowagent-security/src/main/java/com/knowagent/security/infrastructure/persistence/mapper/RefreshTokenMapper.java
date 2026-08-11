package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.typehandler.PostgresInetTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

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

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, tenant_id, user_id, family_id, parent_token_id, token_hash, status,
                   issued_at, expires_at, consumed_at, revoked_at, revoke_reason, issued_ip,
                   user_agent, version
            FROM refresh_tokens
            WHERE token_hash = #{tokenHash}
            LIMIT 1
            FOR UPDATE
            """)
    @Results({
            @Result(column = "issued_ip", property = "issuedIp", typeHandler = PostgresInetTypeHandler.class)
    })
    RefreshTokenPo selectByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
