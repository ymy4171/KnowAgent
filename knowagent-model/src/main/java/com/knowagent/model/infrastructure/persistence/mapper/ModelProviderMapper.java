package com.knowagent.model.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.model.infrastructure.persistence.entity.ModelProviderPo;
import com.knowagent.model.infrastructure.persistence.typehandler.CapabilitySetJsonbTypeHandler;
import com.knowagent.model.infrastructure.persistence.typehandler.EnabledModelsJsonbTypeHandler;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for {@code model_providers}.
 *
 * <p>Every statement here runs inside an authenticated request, so it deliberately
 * stays under the tenant-line plugin: each custom SQL carries an explicit
 * {@code tenant_id} condition and the interceptor additionally injects the context
 * tenant as a fail-closed backstop (the same pattern as the user-management queries).
 * No method bypasses the tenant line.
 */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProviderPo> {

    String COLUMNS = """
            id, tenant_id, provider_key, display_name, adapter_type, base_url, embedding_base_url,
            rerank_base_url, secret_ciphertext, secret_key_version, headers_ciphertext, capabilities,
            enabled_models, public_config, enabled, health_status, config_version, created_by, updated_by,
            version, created_at, updated_at, deleted_at
            """;

    @Results(id = "modelProviderPoResultMap", value = {
            @Result(column = "capabilities", property = "capabilities",
                    typeHandler = CapabilitySetJsonbTypeHandler.class),
            @Result(column = "enabled_models", property = "enabledModels",
                    typeHandler = EnabledModelsJsonbTypeHandler.class),
            @Result(column = "public_config", property = "publicConfig",
                    typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM model_providers
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            LIMIT 1
            """)
    ModelProviderPo selectByIdAndTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @ResultMap("modelProviderPoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM model_providers
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            FOR UPDATE
            """)
    ModelProviderPo selectByIdAndTenantForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @ResultMap("modelProviderPoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM model_providers
            WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL
            FOR KEY SHARE
            """)
    ModelProviderPo selectByIdAndTenantForKeyShare(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @ResultMap("modelProviderPoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM model_providers
            WHERE tenant_id = #{tenantId} AND provider_key = #{providerKey} AND deleted_at IS NULL
            LIMIT 1
            """)
    ModelProviderPo selectActiveByKey(@Param("tenantId") UUID tenantId, @Param("providerKey") String providerKey);

    @ResultMap("modelProviderPoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM model_providers
            WHERE tenant_id = #{tenantId} AND deleted_at IS NULL
            ORDER BY created_at DESC, id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ModelProviderPo> selectPage(@Param("tenantId") UUID tenantId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM model_providers
            WHERE tenant_id = #{tenantId} AND deleted_at IS NULL
            """)
    long countAll(@Param("tenantId") UUID tenantId);

    @Update("""
            UPDATE model_providers
            SET provider_key = #{providerKey},
                display_name = #{displayName},
                adapter_type = #{adapterType},
                base_url = #{baseUrl},
                embedding_base_url = #{embeddingBaseUrl},
                rerank_base_url = #{rerankBaseUrl},
                secret_ciphertext = #{secretCiphertext},
                secret_key_version = #{secretKeyVersion},
                headers_ciphertext = #{headersCiphertext},
                capabilities = #{capabilities, typeHandler=com.knowagent.model.infrastructure.persistence.typehandler.CapabilitySetJsonbTypeHandler},
                enabled_models = #{enabledModels, typeHandler=com.knowagent.model.infrastructure.persistence.typehandler.EnabledModelsJsonbTypeHandler},
                public_config = #{publicConfig, typeHandler=com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler},
                enabled = #{enabled},
                health_status = #{healthStatus},
                config_version = #{configVersion},
                updated_by = #{updatedBy},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND deleted_at IS NULL
              AND version = #{version}
            """)
    int updateConfig(@Param("tenantId") UUID tenantId,
                     @Param("id") UUID id,
                     @Param("providerKey") String providerKey,
                     @Param("displayName") String displayName,
                     @Param("adapterType") AdapterType adapterType,
                     @Param("baseUrl") String baseUrl,
                     @Param("embeddingBaseUrl") String embeddingBaseUrl,
                     @Param("rerankBaseUrl") String rerankBaseUrl,
                     @Param("secretCiphertext") String secretCiphertext,
                     @Param("secretKeyVersion") Integer secretKeyVersion,
                     @Param("headersCiphertext") String headersCiphertext,
                     @Param("capabilities") Set<ModelCapability> capabilities,
                     @Param("enabledModels") List<EnabledModel> enabledModels,
                     @Param("publicConfig") JsonNode publicConfig,
                     @Param("enabled") boolean enabled,
                     @Param("healthStatus") HealthStatus healthStatus,
                     @Param("configVersion") long configVersion,
                     @Param("updatedBy") UUID updatedBy,
                     @Param("version") long version);

    @Update("""
            UPDATE model_providers
            SET deleted_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND deleted_at IS NULL
              AND version = #{version}
            """)
    int softDelete(@Param("tenantId") UUID tenantId,
                   @Param("id") UUID id,
                   @Param("version") long version);
}
