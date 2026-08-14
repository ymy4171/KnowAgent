package com.knowagent.security.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.security.infrastructure.persistence.entity.TenantPo;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
@InterceptorIgnore(tenantLine = "1")
public interface TenantMapper extends BaseMapper<TenantPo> {
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, slug, name, status, settings, version, created_at, updated_at, deleted_at
            FROM tenants
            WHERE slug = #{slug}
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            LIMIT 1
            """)
    @Results(id = "tenantPoResultMap", value = {
            @Result(column = "settings", property = "settings", typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    TenantPo selectActiveBySlug(@Param("slug") String slug);

    /**
     * Active-tenant lookup by id, used by the current-user endpoint. {@code tenants}
     * is the tenant root table (no {@code tenant_id} column), so the tenant-line
     * handler already ignores it; the bypass annotation is kept for the same reason
     * as {@link #selectActiveBySlug}.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, slug, name, status, settings, version, created_at, updated_at, deleted_at
            FROM tenants
            WHERE id = #{id}
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            LIMIT 1
            """)
    @Results({
            @Result(column = "settings", property = "settings", typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    TenantPo selectActiveById(@Param("id") UUID id);

    /**
     * Bootstrap idempotency lookup: finds a tenant by slug regardless of status.
     * {@code tenants} is the tenant root table (no {@code tenant_id} column), so the
     * tenant-line handler already ignores it; the bypass annotation is kept for the
     * same reason as {@link #selectActiveBySlug} and is locked by
     * {@code SecurityMapperSqlContractTest}.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT id, slug, name, status, settings, version, created_at, updated_at, deleted_at
            FROM tenants
            WHERE slug = #{slug}
              AND deleted_at IS NULL
            LIMIT 1
            """)
    @Results({
            @Result(column = "settings", property = "settings", typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    TenantPo selectBySlug(@Param("slug") String slug);
}
