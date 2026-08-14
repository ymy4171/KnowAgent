package com.knowagent.api.auth;

import com.knowagent.api.auth.dto.LoginRequest;
import com.knowagent.api.auth.dto.LoginResponse;
import com.knowagent.api.auth.dto.RefreshTokenRequest;
import com.knowagent.api.security.AccessTokenIssuer;
import com.knowagent.api.security.IssuedAccessToken;
import com.knowagent.security.application.service.Login;
import com.knowagent.security.application.service.LoginCommand;
import com.knowagent.security.application.service.LoginResult;
import com.knowagent.security.application.service.LogoutCommand;
import com.knowagent.security.application.service.RefreshCommand;
import com.knowagent.security.application.service.RefreshTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Public authentication endpoints: login, refresh-token rotation and logout.
 *
 * <p>The three endpoints are on the security chain's permit list, so no Access
 * Token is required. Authentication itself lives in {@link Login} and rotation in
 * {@link RefreshTokens} (both {@code knowagent-security} application services). The
 * API-layer {@link RefreshAuthenticationService} coordinates rotation with the JWT
 * infrastructure so both credentials are produced before the rotation transaction
 * commits, without making the security module depend on a web/JWT implementation.
 *
 * <p>The responses carry the signed Access Token and the one-time raw Refresh
 * Token. Neither value is ever logged or persisted in raw form - the Refresh Token
 * reaches the database only as a SHA-256 hash.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private final Login login;
    private final RefreshTokens refreshTokens;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshAuthenticationService refreshAuthenticationService;

    public AuthController(
            Login login,
            RefreshTokens refreshTokens,
            AccessTokenIssuer accessTokenIssuer,
            RefreshAuthenticationService refreshAuthenticationService) {
        this.login = login;
        this.refreshTokens = refreshTokens;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshAuthenticationService = refreshAuthenticationService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        LoginResult result = login.login(new LoginCommand(
                request.tenantSlug(),
                request.loginName(),
                request.password(),
                resolveClientIp(http),
                http.getHeader(HttpHeaders.USER_AGENT)));

        IssuedAccessToken accessToken = accessTokenIssuer.issue(result.principal());
        long expiresInSeconds = Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).getSeconds();
        return new LoginResponse(TOKEN_TYPE_BEARER, accessToken.value(), result.refreshToken(), expiresInSeconds);
    }

    /**
     * Rotates a one-time Refresh Token: the presented token is consumed and a
     * successor is issued in the same family, then a fresh Access Token is signed.
     * The response has the same shape as login, so a client can treat both endpoints
     * identically. A replay or any other invalid token yields the stable JSON 401.
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest http) {
        return refreshAuthenticationService.refresh(new RefreshCommand(
                request.refreshToken(),
                resolveClientIp(http),
                http.getHeader(HttpHeaders.USER_AGENT)));
    }

    /**
     * Revokes every still-active token in the family the submitted Refresh Token
     * belongs to. Repeated logout is idempotent: unknown tokens and already-revoked
     * families are accepted silently, so no session state is ever revealed.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokens.logout(new LogoutCommand(request.refreshToken()));
    }

    /**
     * The remote address recorded on the issued Refresh Token. Parsing is
     * best-effort: an unparseable value (for example a blank address) yields
     * {@code null}, which maps to the nullable {@code issued_ip} column.
     */
    private static InetAddress resolveClientIp(HttpServletRequest http) {
        String remoteAddr = http.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remoteAddr);
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
