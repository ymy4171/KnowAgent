package com.knowagent.api.security;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a validated {@link Jwt} into a {@link JwtTenantAuthenticationToken} whose
 * principal is a {@link TenantPrincipal}.
 *
 * <p>Claim mapping (the Access Token contract):
 * <ul>
 *   <li>{@code sub} - the user id (a UUID); a missing or malformed value fails
 *       authentication.</li>
 *   <li>{@code tenant_id} - the tenant id (a UUID); a missing or malformed value
 *       fails authentication so a token can never claim a bogus tenant.</li>
 *   <li>{@code roles} - raw role codes, mapped to authorities as
 *       {@code ROLE_<code>}. Must be an array of non-empty strings; an empty
 *       array is valid and means the user holds no roles.</li>
 *   <li>{@code permissions} - stable permission codes from
 *       {@code SecurityPermissions}, mapped to authorities verbatim. Must be an
 *       array of non-empty strings; an empty array is valid and means the user
 *       holds no permissions.</li>
 * </ul>
 *
 * <p>A {@code roles} or {@code permissions} claim that is missing, a single
 * string, or that contains numbers, booleans, nulls or empty strings is a type
 * error and fails authentication - claim values are never coerced via
 * {@code String.valueOf}. An empty array completes authentication successfully
 * so the user receives a 403 (not 401) when accessing a protected resource
 * they lack the role or permission for.</p>
 *
 * <p>The converter runs outside the provider's exception handling, so claim
 * failures must surface as an {@link AuthenticationException}; an
 * {@link InvalidBearerTokenException} makes the resource server return HTTP 401
 * with an {@code invalid_token} challenge. Messages never include the token value.
 */
public final class JwtToTenantAuthenticationConverter implements Converter<Jwt, JwtTenantAuthenticationToken> {

    @Override
    public JwtTenantAuthenticationToken convert(Jwt jwt) {
        UUID tenantId = uuidClaim(jwt, "tenant_id");
        UUID userId = uuidClaim(jwt, "sub");
        Set<String> roles = stringSetClaim(jwt, "roles");
        Set<String> permissions = stringSetClaim(jwt, "permissions");

        TenantPrincipal principal = new TenantPrincipal(TenantId.of(tenantId), userId, roles);
        List<GrantedAuthority> authorities = new ArrayList<>(roles.size() + permissions.size());
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

        return new JwtTenantAuthenticationToken(principal, authorities, jwt);
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        Object raw = jwt.getClaim(name);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new InvalidBearerTokenException("JWT is missing required claim '" + name + "'");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // The (String, Throwable) constructor surfaces the cause's message
            // rather than this one, so the friendly text is passed alone.
            throw new InvalidBearerTokenException("JWT claim '" + name + "' is not a valid UUID");
        }
    }

    private static Set<String> stringSetClaim(Jwt jwt, String name) {
        Object raw = jwt.getClaim(name);
        // The claim must be present (RequiredClaimsValidator enforces this, so a
        // missing claim here indicates a misconfigured decoder).
        if (raw == null) {
            throw new InvalidBearerTokenException("JWT is missing required claim '" + name + "'");
        }
        // A single string, a number, a boolean, or null is a type error - the
        // contract requires an array even when the array happens to be empty.
        if (!(raw instanceof Collection<?> values)) {
            throw new InvalidBearerTokenException("JWT claim '" + name + "' must be a string array");
        }
        Set<String> result = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new InvalidBearerTokenException(
                        "JWT claim '" + name + "' must contain only non-empty strings");
            }
            result.add(text);
        }
        // Empty arrays are valid: a user with no roles or no permissions is
        // authenticated but will receive a 403 when accessing a resource that
        // requires a specific role or permission.
        return result;
    }
}
