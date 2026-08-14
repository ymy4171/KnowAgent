package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.TenantRepository;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.entity.TenantPo;
import com.knowagent.security.infrastructure.persistence.mapper.TenantMapper;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class MyBatisTenantRepository implements TenantRepository {
    private final TenantMapper mapper;

    public MyBatisTenantRepository(TenantMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Optional<Tenant> findActiveBySlug(String slug) {
        Objects.requireNonNull(slug, "slug must not be null");
        TenantPo record = mapper.selectActiveBySlug(slug);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public Optional<Tenant> findActiveById(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        TenantPo record = mapper.selectActiveById(tenantId.value());
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }
}
