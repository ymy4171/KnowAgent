package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.RoleRepository;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.mapper.RoleMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class MyBatisRoleRepository implements RoleRepository {
    private final RoleMapper mapper;

    public MyBatisRoleRepository(RoleMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public List<Role> findEffectiveByUser(TenantId tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return mapper.selectEffectiveByUser(tenantId.value(), userId).stream()
                .map(IdentityPersistenceConverter::toDomain)
                .toList();
    }
}
