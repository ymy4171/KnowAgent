package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.security.application.port.out.UserRoleStore;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.mapper.UserRoleMapper;
import org.springframework.stereotype.Repository;

import java.util.Objects;

@Repository
public class MyBatisUserRoleStore implements UserRoleStore {
    private final UserRoleMapper mapper;

    public MyBatisUserRoleStore(UserRoleMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public void insert(UserRole userRole) {
        Objects.requireNonNull(userRole, "userRole must not be null");
        int inserted = mapper.insert(IdentityPersistenceConverter.toPersistence(userRole));
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to persist user role assignment");
        }
    }
}
