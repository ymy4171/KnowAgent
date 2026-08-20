package com.knowagent.model.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.infrastructure.persistence.converter.ModelProviderPersistenceConverter;
import com.knowagent.model.infrastructure.persistence.entity.ModelProviderPo;
import com.knowagent.model.infrastructure.persistence.mapper.ModelProviderMapper;
import com.knowagent.model.provider.ModelProvider;
import com.knowagent.model.provider.ModelProviderPage;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisModelProviderRepository implements ModelProviderRepository {

    private final ModelProviderMapper mapper;

    public MyBatisModelProviderRepository(ModelProviderMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public void save(ModelProvider provider) {
        mapper.insert(ModelProviderPersistenceConverter.toPersistence(provider));
    }

    @Override
    public Optional<ModelProvider> findById(TenantId tenantId, UUID id) {
        ModelProviderPo record = mapper.selectByIdAndTenant(tenantId.value(), id);
        return Optional.ofNullable(record).map(ModelProviderPersistenceConverter::toDomain);
    }

    @Override
    public Optional<ModelProvider> findByIdForUpdate(TenantId tenantId, UUID id) {
        ModelProviderPo record = mapper.selectByIdAndTenantForUpdate(tenantId.value(), id);
        return Optional.ofNullable(record).map(ModelProviderPersistenceConverter::toDomain);
    }

    @Override
    public Optional<ModelProvider> findByIdForKeyShare(TenantId tenantId, UUID id) {
        ModelProviderPo record = mapper.selectByIdAndTenantForKeyShare(tenantId.value(), id);
        return Optional.ofNullable(record).map(ModelProviderPersistenceConverter::toDomain);
    }

    @Override
    public Optional<ModelProvider> findActiveByKey(TenantId tenantId, String providerKey) {
        ModelProviderPo record = mapper.selectActiveByKey(tenantId.value(), providerKey);
        return Optional.ofNullable(record).map(ModelProviderPersistenceConverter::toDomain);
    }

    @Override
    public ModelProviderPage page(TenantId tenantId, int page, int size) {
        long total = mapper.countAll(tenantId.value());
        List<ModelProvider> providers = mapper.selectPage(tenantId.value(), size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(ModelProviderPersistenceConverter::toDomain)
                .toList();
        return new ModelProviderPage(providers, total);
    }

    @Override
    public int updateConfig(ModelProvider provider) {
        return mapper.updateConfig(
                provider.tenantId().value(),
                provider.id(),
                provider.providerKey(),
                provider.displayName(),
                provider.adapterType(),
                provider.baseUrl(),
                provider.embeddingBaseUrl(),
                provider.rerankBaseUrl(),
                provider.secret() == null ? null : provider.secret().envelope(),
                keyVersion(provider),
                provider.headers() == null ? null : provider.headers().envelope(),
                provider.capabilities(),
                provider.enabledModels(),
                provider.publicConfig(),
                provider.enabled(),
                provider.healthStatus(),
                provider.configVersion(),
                provider.updatedBy(),
                provider.version());
    }

    @Override
    public int softDelete(TenantId tenantId, UUID id, long version) {
        return mapper.softDelete(tenantId.value(), id, version);
    }

    private static Integer keyVersion(ModelProvider provider) {
        if (provider.secret() != null) {
            return provider.secret().keyVersion();
        }
        if (provider.headers() != null) {
            return provider.headers().keyVersion();
        }
        return null;
    }
}
