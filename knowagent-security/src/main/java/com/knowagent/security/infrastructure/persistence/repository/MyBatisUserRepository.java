package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import com.knowagent.security.infrastructure.persistence.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class MyBatisUserRepository implements UserRepository {
    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Optional<User> findByTenantAndLoginName(TenantId tenantId, String loginName) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        UserPo record = mapper.selectByTenantAndLoginName(tenantId.value(), loginName);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }
}
