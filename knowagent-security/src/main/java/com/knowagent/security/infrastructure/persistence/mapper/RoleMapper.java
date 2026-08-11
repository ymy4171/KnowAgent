package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.RolePo;
import com.knowagent.security.infrastructure.persistence.typehandler.PermissionSetJsonbTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface RoleMapper extends BaseMapper<RolePo> {
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT r.id, r.tenant_id, r.code, r.name, r.description, r.permissions, r.is_system,
                   r.status, r.version, r.created_at, r.updated_at, r.deleted_at
            FROM user_roles ur
            JOIN roles r
              ON r.tenant_id = ur.tenant_id
             AND r.id = ur.role_id
            JOIN users u
              ON u.tenant_id = ur.tenant_id
             AND u.id = ur.user_id
            WHERE ur.tenant_id = #{tenantId}
              AND r.tenant_id = #{tenantId}
              AND u.tenant_id = #{tenantId}
              AND ur.user_id = #{userId}
              AND u.deleted_at IS NULL
              AND r.status = 'ACTIVE'
              AND r.deleted_at IS NULL
              AND (ur.expires_at IS NULL OR ur.expires_at > CURRENT_TIMESTAMP)
            ORDER BY r.code
            """)
    @Results(id = "rolePoResultMap", value = {
            @Result(column = "permissions", property = "permissions", typeHandler = PermissionSetJsonbTypeHandler.class)
    })
    List<RolePo> selectEffectiveByUser(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId);
}
