package com.knowagent.api.security;

import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Objects;

/**
 * Authenticated principal derived from a validated Access Token.
 *
 * <p>{@code JwtAuthenticationToken} would keep the raw {@link Jwt} as the
 * principal, but {@link com.knowagent.security.context.TenantContext} (and the
 * {@code TenantContextFilter}) only recognises a principal that is a
 * {@link TenantPrincipal}. This token therefore carries the tenant principal as
 * its principal while still exposing the source JWT for callers that need its
 * {@code jti} (for example future token-revocation checks).
 *
 * <p>{@link #toString()} deliberately omits the JWT value: tokens must never leak
 * through logs, responses or exception messages.
 */
public final class JwtTenantAuthenticationToken extends AbstractAuthenticationToken {

    private final TenantPrincipal principal;
    private final Jwt jwt;

    public JwtTenantAuthenticationToken(
            TenantPrincipal principal,
            Collection<? extends GrantedAuthority> authorities,
            Jwt jwt) {
        super(authorities);
        this.principal = Objects.requireNonNull(principal, "principal must not be null");
        this.jwt = Objects.requireNonNull(jwt, "jwt must not be null");
        setAuthenticated(true);
    }

    @Override
    public TenantPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    public Jwt jwt() {
        return jwt;
    }

    @Override
    public String toString() {
        return "JwtTenantAuthenticationToken[principal=" + principal
                + ", authenticated=" + isAuthenticated()
                + ", grantedAuthorities=" + getAuthorities() + "]";
    }
}
