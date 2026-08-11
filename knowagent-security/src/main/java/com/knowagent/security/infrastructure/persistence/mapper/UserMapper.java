package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
