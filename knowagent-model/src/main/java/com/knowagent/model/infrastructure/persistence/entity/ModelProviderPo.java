package com.knowagent.model.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.model.infrastructure.persistence.typehandler.CapabilitySetJsonbTypeHandler;
import com.knowagent.model.infrastructure.persistence.typehandler.EnabledModelsJsonbTypeHandler;
import com.knowagent.model.provider.AdapterType;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.HealthStatus;
import com.knowagent.model.provider.ModelCapability;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@TableName(value = "model_providers", autoResultMap = true)
public class ModelProviderPo {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID tenantId;
    private String providerKey;
    private String displayName;
    private AdapterType adapterType;
    private String baseUrl;
    private String embeddingBaseUrl;
    private String rerankBaseUrl;
    private String secretCiphertext;
    private Integer secretKeyVersion;
    private String headersCiphertext;
    @TableField(typeHandler = CapabilitySetJsonbTypeHandler.class)
    private Set<ModelCapability> capabilities;
    @TableField(typeHandler = EnabledModelsJsonbTypeHandler.class)
    private List<EnabledModel> enabledModels;
    @TableField(typeHandler = JsonNodeJsonbTypeHandler.class)
    private JsonNode publicConfig;
    private Boolean enabled;
    private HealthStatus healthStatus;
    private Long configVersion;
    private UUID createdBy;
    private UUID updatedBy;
    @Version
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public AdapterType getAdapterType() { return adapterType; }
    public void setAdapterType(AdapterType adapterType) { this.adapterType = adapterType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }
    public String getRerankBaseUrl() { return rerankBaseUrl; }
    public void setRerankBaseUrl(String rerankBaseUrl) { this.rerankBaseUrl = rerankBaseUrl; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(String secretCiphertext) { this.secretCiphertext = secretCiphertext; }
    public Integer getSecretKeyVersion() { return secretKeyVersion; }
    public void setSecretKeyVersion(Integer secretKeyVersion) { this.secretKeyVersion = secretKeyVersion; }
    public String getHeadersCiphertext() { return headersCiphertext; }
    public void setHeadersCiphertext(String headersCiphertext) { this.headersCiphertext = headersCiphertext; }
    public Set<ModelCapability> getCapabilities() { return capabilities; }
    public void setCapabilities(Set<ModelCapability> capabilities) { this.capabilities = capabilities; }
    public List<EnabledModel> getEnabledModels() { return enabledModels; }
    public void setEnabledModels(List<EnabledModel> enabledModels) { this.enabledModels = enabledModels; }
    public JsonNode getPublicConfig() { return publicConfig; }
    public void setPublicConfig(JsonNode publicConfig) { this.publicConfig = publicConfig; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public HealthStatus getHealthStatus() { return healthStatus; }
    public void setHealthStatus(HealthStatus healthStatus) { this.healthStatus = healthStatus; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
