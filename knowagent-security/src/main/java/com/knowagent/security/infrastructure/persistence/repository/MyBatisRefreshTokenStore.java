package com.knowagent.security.infrastructure.persistence.repository;

import com.knowagent.security.application.port.out.RefreshTokenStore;
import com.knowagent.security.domain.token.RefreshToken;
import com.knowagent.security.infrastructure.persistence.converter.IdentityPersistenceConverter;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.mapper.RefreshTokenMapper;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class MyBatisRefreshTokenStore implements RefreshTokenStore {
    private final RefreshTokenMapper mapper;

    public MyBatisRefreshTokenStore(RefreshTokenMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        requireHash(tokenHash);
        RefreshTokenPo record = mapper.selectByTokenHash(tokenHash);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    @Override
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        requireHash(tokenHash);
        RefreshTokenPo record = mapper.selectByTokenHashForUpdate(tokenHash);
        return Optional.ofNullable(record).map(IdentityPersistenceConverter::toDomain);
    }

    private static void requireHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (!tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hexadecimal value");
        }
    }
}
