package com.knowagent.api.auth;

import com.knowagent.api.auth.dto.LoginResponse;
import com.knowagent.api.security.AccessTokenIssuer;
import com.knowagent.api.security.IssuedAccessToken;
import com.knowagent.security.application.service.LoginResult;
import com.knowagent.security.application.service.RefreshCommand;
import com.knowagent.security.application.service.RefreshTokenInvalidException;
import com.knowagent.security.application.service.RefreshTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;

/**
 * API-layer transaction boundary for a complete credential refresh.
 *
 * <p>The security module owns token-family rotation while this module owns JWT
 * signing. Coordinating both here keeps the database transaction open until the
 * Access Token and response have been created: a signing failure rolls the parent
 * consume and child insert back together. Replay rejection is deliberately
 * excluded from rollback so its family revocation remains committed.
 */
@Service
public class RefreshAuthenticationService {

    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private final RefreshTokens refreshTokens;
    private final AccessTokenIssuer accessTokenIssuer;

    public RefreshAuthenticationService(RefreshTokens refreshTokens, AccessTokenIssuer accessTokenIssuer) {
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens must not be null");
        this.accessTokenIssuer = Objects.requireNonNull(accessTokenIssuer, "accessTokenIssuer must not be null");
    }

    @Transactional(noRollbackFor = RefreshTokenInvalidException.class)
    public LoginResponse refresh(RefreshCommand command) {
        LoginResult result = refreshTokens.refresh(Objects.requireNonNull(command, "command must not be null"));
        IssuedAccessToken accessToken = accessTokenIssuer.issue(result.principal());
        long expiresInSeconds = Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).getSeconds();
        return new LoginResponse(TOKEN_TYPE_BEARER, accessToken.value(), result.refreshToken(), expiresInSeconds);
    }
}
