package com.knowagent.api.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;

/**
 * Rejects tokens that miss any claim the Access Token contract requires.
 *
 * <p>Signature, issuer, audience and expiry are handled by Spring Security's
 * default validators, but the default timestamp validator only checks {@code exp}
 * when the claim is present - it never requires it. A token with a valid signature
 * but no {@code exp} would otherwise never expire, so {@code exp} and {@code iat}
 * are required here alongside the tenant identity claims that {@link
 * JwtToTenantAuthenticationConverter} and {@link TenantPrincipal}-based
 * authorization depend on. A token without these claims is malformed and must
 * fail authentication - the error description only lists the missing claim
 * names and never the token value.
 */
public final class RequiredClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final Set<String> REQUIRED_CLAIMS = Set.of(
            "exp", "iat", "sub", "tenant_id", "roles", "permissions", "jti");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> missing = REQUIRED_CLAIMS.stream()
                .filter(claim -> jwt.getClaim(claim) == null)
                .sorted()
                .toList();
        if (missing.isEmpty()) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "missing_required_claim",
                "JWT is missing required claims: " + missing,
                null));
    }
}
