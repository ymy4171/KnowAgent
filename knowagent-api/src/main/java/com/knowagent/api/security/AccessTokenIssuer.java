package com.knowagent.api.security;

import com.knowagent.security.principal.TenantPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mints signed Access Tokens with Spring Security's JOSE encoder (no hand-written
 * codec).
 *
 * <p>The token carries the Access Token contract claims: {@code iss},
 * {@code aud}, {@code sub} (user id), {@code tenant_id}, {@code roles},
 * {@code permissions}, {@code jti}, {@code iat} and {@code exp}. {@code jti} is a
 * fresh UUID per token. Roles and permissions are supplied by the caller (the
 * future login flow resolves the user's effective roles from the database); this
 * service only signs what it is given and never queries persistence itself.
 */
@Service
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public IssuedAccessToken issue(TenantPrincipal principal, Set<String> permissions) {
        Objects.requireNonNull(principal, "principal must not be null");
        Set<String> grantedPermissions =
                Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(principal.userId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", principal.tenantId().value().toString())
                .claim("roles", principal.roles())
                .claim("permissions", grantedPermissions)
                .build();

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new IssuedAccessToken(jwt.getTokenValue(), now, expiresAt);
    }
}
