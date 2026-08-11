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

@Mapper
@InterceptorIgnore(tenantLine = "1")
public interface TenantMapper extends BaseMapper<TenantPo> {
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
}
