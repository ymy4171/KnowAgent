package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.UserRepository;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.domain.user.UserPage;
import com.knowagent.security.domain.user.UserStatus;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import com.knowagent.security.infrastructure.persistence.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

    @Override
    public Optional<User> findById(TenantId tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        UserPo record = mapper.selectByIdAndTenant(tenantId.value(), userId);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public boolean updateLoginState(User user) {
        Objects.requireNonNull(user, "user must not be null");
        return mapper.updateLoginState(IdentityPersistenceConverter.toPersistence(user)) == 1;
    }

    @Override
    public int recordLoginFailure(TenantId tenantId, UUID userId, Instant now,
                                  int maxFailedAttempts, Instant lockUntil) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(lockUntil, "lockUntil must not be null");
        return mapper.recordLoginFailure(tenantId.value(), userId, now, maxFailedAttempts, lockUntil);
    }

    @Override
    public UserPage search(TenantId tenantId, String keywordPattern, UserStatus status,
                           int page, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("page and size must be >= 1");
        }
        String statusName = status == null ? null : status.name();
        long total = mapper.countUsers(tenantId.value(), keywordPattern, statusName);
        List<UserPo> records = mapper.selectUserPage(
                tenantId.value(), keywordPattern, statusName, size, Math.multiplyExact(page - 1, size));
        List<User> users = records.stream()
                .map(IdentityPersistenceConverter::toDomain)
                .toList();
        return new UserPage(users, total);
    }
}
