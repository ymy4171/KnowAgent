package com.knowagent.security.application.port.out;

import com.knowagent.security.domain.token.RefreshToken;

import java.util.Optional;

public interface RefreshTokenStore {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Loads and locks one token row. The caller must already own a surrounding database transaction.
     */
    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);
}
