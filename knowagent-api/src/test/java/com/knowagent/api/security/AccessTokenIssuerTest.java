package com.knowagent.api.security;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.principal.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenIssuerTest {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "access-token-test-secret-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8));
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final JwtProperties PROPERTIES = new JwtProperties(ISSUER, AUDIENCE, SECRET, TTL);
    private static final JwtEncoder ENCODER = JwtTestSupport.secretKeyEncoder(PROPERTIES.hmacKey());
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final AccessTokenIssuer issuer = new AccessTokenIssuer(ENCODER, PROPERTIES);

    @Test
    void issuedTokenCarriesTheAccessTokenContractClaims() {
        TenantPrincipal principal =
                new TenantPrincipal(TenantId.of(TENANT_ID), USER_ID, Set.of("ADMIN", "ANALYST"),
                        Set.of("USER_READ", "TENANT_WRITE"));
        IssuedAccessToken issued = issuer.issue(principal);

        Jwt decoded = rawDecoder().decode(issued.value());

        // JWT numeric-date claims carry second precision, so the issued instants
        // are truncated before comparing with the values decoded from the token.
        assertThat(issued.issuedAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(decoded.getIssuedAt());
        assertThat(issued.expiresAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(decoded.getExpiresAt());
        assertThat(Duration.between(issued.issuedAt(), issued.expiresAt())).isEqualTo(TTL);
        assertThat(issued.expiresAt()).isAfter(Instant.now());

        assertThat(decoded.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(decoded.getAudience()).contains(AUDIENCE);
        assertThat(decoded.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(decoded.getClaimAsString("tenant_id")).isEqualTo(TENANT_ID.toString());
        assertThat(decoded.getClaimAsStringList("roles")).containsExactlyInAnyOrder("ADMIN", "ANALYST");
        assertThat(decoded.getClaimAsStringList("permissions"))
                .containsExactlyInAnyOrder("USER_READ", "TENANT_WRITE");
        assertThat((String) decoded.getClaim("jti")).isNotBlank();
    }

    @Test
    void issuedTokenPassesSignatureIssuerAudienceAndRequiredClaimValidation() {
        TenantPrincipal principal = new TenantPrincipal(TenantId.of(TENANT_ID), USER_ID, Set.of("ADMIN"),
                Set.of("USER_READ"));
        IssuedAccessToken issued = issuer.issue(principal);

        Jwt decoded = validatedDecoder().decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(decoded.getClaimAsString("tenant_id")).isEqualTo(TENANT_ID.toString());
    }

    @Test
    void roundTripWithEmptyRolesAndPermissionsAuthenticatesWithoutAuthorities() {
        // The issuer allows empty roles/permissions; the converter must accept them
        // so the service never signs a token it cannot authenticate. The user is
        // authenticated but carries no authorities - accessing a protected resource
        // will yield 403, not 401.
        TenantPrincipal principal = new TenantPrincipal(TenantId.of(TENANT_ID), USER_ID, Set.of(), Set.of());
        IssuedAccessToken issued = issuer.issue(principal);

        Jwt decoded = validatedDecoder().decode(issued.value());
        JwtTenantAuthenticationToken token = new JwtToTenantAuthenticationConverter().convert(decoded);

        assertThat(token.getPrincipal().tenantId().value()).isEqualTo(TENANT_ID);
        assertThat(token.getPrincipal().userId()).isEqualTo(USER_ID);
        assertThat(token.getPrincipal().roles()).isEmpty();
        assertThat(token.getPrincipal().permissions()).isEmpty();
        assertThat(token.getAuthorities()).isEmpty();
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getCredentials()).isNull();
    }

    @Test
    void toStringNeverExposesTheTokenValue() {
        TenantPrincipal principal = new TenantPrincipal(TenantId.of(TENANT_ID), USER_ID, Set.of("ADMIN"), Set.of());
        IssuedAccessToken issued = issuer.issue(principal);

        assertThat(issued.toString()).doesNotContain(issued.value());
        assertThat(issuer.toString()).doesNotContain(issued.value());
    }

    private static JwtDecoder rawDecoder() {
        return NimbusJwtDecoder.withSecretKey(PROPERTIES.hmacKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static JwtDecoder validatedDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(PROPERTIES.hmacKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtAudienceValidator(AUDIENCE),
                new RequiredClaimsValidator()));
        decoder.setClaimSetConverter(
                new AccessTokenAuthenticationConfiguration.PreserveAbsentIatClaimSetConverter());
        return decoder;
    }
}
