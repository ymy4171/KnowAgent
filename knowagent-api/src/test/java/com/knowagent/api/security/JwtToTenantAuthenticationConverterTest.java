package com.knowagent.api.security;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.principal.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtToTenantAuthenticationConverterTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "converter-test-secret-0123456789abcdefghi".getBytes(StandardCharsets.UTF_8));
    private static final JwtProperties PROPERTIES =
            new JwtProperties("https://knowagent.test", "knowagent-api", SECRET, Duration.ofMinutes(15));
    private static final JwtEncoder ENCODER = JwtTestSupport.secretKeyEncoder(PROPERTIES.hmacKey());
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final JwtToTenantAuthenticationConverter converter = new JwtToTenantAuthenticationConverter();

    @Test
    void validJwtMapsToTenantPrincipalAndAuthorities() {
        JwtTenantAuthenticationToken token = converter.convert(sign(baseClaims().build()));
        TenantPrincipal principal = token.getPrincipal();

        assertThat(principal.tenantId().value()).isEqualTo(TENANT_ID);
        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.roles()).containsExactlyInAnyOrder("ADMIN", "ANALYST");
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ANALYST", "USER_READ", "TENANT_WRITE");
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getCredentials()).isNull();
    }

    @Test
    void absentTenantIdFailsAuthentication() {
        // tenant_id cannot be removed from the shared builder, so a fresh claims
        // set without the tenant claim is used.
        Instant now = Instant.now();
        JwtClaimsSet withoutTenant = JwtClaimsSet.builder()
                .issuer("https://knowagent.test")
                .audience(List.of("knowagent-api"))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("USER_READ"))
                .build();

        assertThatThrownBy(() -> converter.convert(sign(withoutTenant)))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void malformedTenantIdFailsAuthentication() {
        JwtClaimsSet claims = baseClaims().claim("tenant_id", "not-a-uuid").build();

        assertThatThrownBy(() -> converter.convert(sign(claims)))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("not a valid UUID");
    }

    @Test
    void absentSubjectFailsAuthentication() {
        Instant now = Instant.now();
        JwtClaimsSet withoutSubject = JwtClaimsSet.builder()
                .issuer("https://knowagent.test")
                .audience(List.of("knowagent-api"))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("USER_READ"))
                .build();

        assertThatThrownBy(() -> converter.convert(sign(withoutSubject)))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("sub");
    }

    @Test
    void absentRolesFailAuthentication() {
        Instant now = Instant.now();
        JwtClaimsSet withoutRoles = JwtClaimsSet.builder()
                .issuer("https://knowagent.test")
                .audience(List.of("knowagent-api"))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("permissions", List.of("USER_READ"))
                .build();

        assertThatThrownBy(() -> converter.convert(sign(withoutRoles)))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void absentPermissionsFailAuthentication() {
        Instant now = Instant.now();
        JwtClaimsSet withoutPermissions = JwtClaimsSet.builder()
                .issuer("https://knowagent.test")
                .audience(List.of("knowagent-api"))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("roles", List.of("ADMIN"))
                .build();

        assertThatThrownBy(() -> converter.convert(sign(withoutPermissions)))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("permissions");
    }

    @Test
    void numericRolesAreRejected() {
        assertThatThrownBy(() -> converter.convert(sign(baseClaims().claim("roles", 123).build())))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void nonStringArrayElementsAreRejected() {
        // Numbers, booleans, nulls and empty strings must never be coerced into
        // authorities via String.valueOf - a type error fails authentication.
        assertThatThrownBy(() -> converter.convert(sign(baseClaims().claim("roles", List.of(123)).build())))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(sign(baseClaims().claim("permissions", List.of(true)).build())))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(
                sign(baseClaims().claim("roles", Arrays.asList("ADMIN", null)).build())))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(sign(baseClaims().claim("roles", List.of("")).build())))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void emptyArrayProducesEmptyAuthorities() {
        // A user with no roles or permissions is authenticated but has no
        // authorities - they will receive 403 when accessing protected resources.
        JwtTenantAuthenticationToken token = converter.convert(
                sign(baseClaims().claim("roles", List.of()).claim("permissions", List.of()).build()));

        assertThat(token.getPrincipal().roles()).isEmpty();
        assertThat(token.getAuthorities()).isEmpty();
        assertThat(token.isAuthenticated()).isTrue();
    }

    @Test
    void singleStringClaimIsRejected() {
        // The contract is an array; a bare string is a type error, not a one-role token.
        assertThatThrownBy(() -> converter.convert(sign(baseClaims().claim("roles", "ADMIN").build())))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void tokenToStringNeverIncludesTheJwtValue() {
        Jwt jwt = sign(baseClaims().build());
        JwtTenantAuthenticationToken token = converter.convert(jwt);

        assertThat(token.toString()).doesNotContain(jwt.getTokenValue());
    }

    private JwtClaimsSet.Builder baseClaims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer("https://knowagent.test")
                .audience(List.of("knowagent-api"))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("roles", List.of("ADMIN", "ANALYST"))
                .claim("permissions", List.of("USER_READ", "TENANT_WRITE"));
    }

    private static Jwt sign(JwtClaimsSet claims) {
        return ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims));
    }
}
