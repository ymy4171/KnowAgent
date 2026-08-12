package com.knowagent.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class JwtTokenValidationTest {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "validation-test-secret-0123456789abcdefg".getBytes(StandardCharsets.UTF_8));
    private static final JwtEncoder ENCODER = JwtTestSupport.secretKeyEncoder(
            new JwtProperties(ISSUER, AUDIENCE, SECRET, Duration.ofMinutes(15)).hmacKey());
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final JwtDecoder decoder = decoder(ISSUER, AUDIENCE, SECRET);

    @Test
    void validTokenDecodes() {
        Jwt decoded = decoder.decode(validToken());

        assertThat(decoded.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(decoded.getClaimAsString("tenant_id")).isEqualTo(TENANT_ID.toString());
    }

    @Test
    void expiredTokenIsRejected() {
        assertRejected(token(builder -> {
            // An expired token still needs exp after iat; both sit in the past.
            Instant past = Instant.now().minus(Duration.ofMinutes(30));
            builder.issuedAt(past);
            builder.expiresAt(past.plus(Duration.ofMinutes(15)));
        }));
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        // A signed token with no exp would never expire, so it must be rejected
        // even though the signature is valid.
        assertRejected(tokenWithoutTimeClaim("exp"));
    }

    @Test
    void tokenWithoutIssuedAtIsRejected() {
        assertRejected(tokenWithoutTimeClaim("iat"));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = validToken();
        String tampered = JwtTestSupport.tamperSignature(token);
        assertRejected(tampered);
    }

    @Test
    void wrongIssuerIsRejected() {
        assertRejected(token(builder -> builder.issuer("https://attacker.test")));
    }

    @Test
    void wrongAudienceIsRejected() {
        assertRejected(token(builder -> builder.audience(List.of("another-service"))));
    }

    @Test
    void tokenWithoutRequiredTenantClaimIsRejected() {
        assertRejected(tokenWithoutTenantClaim());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String otherSecret = Base64.getEncoder().encodeToString(
                "a-different-32-byte-min-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        JwtDecoder otherDecoder = decoder(ISSUER, AUDIENCE, otherSecret);

        assertRejected(otherDecoder, validToken());
    }

    private String validToken() {
        return token(builder -> { });
    }

    private String token(Consumer<JwtClaimsSet.Builder> mutation) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("USER_READ"));
        mutation.accept(builder);
        return encode(builder.build());
    }

    private String tokenWithoutTenantClaim() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("USER_READ"))
                .build();
        return encode(claims);
    }

    private String tokenWithoutTimeClaim(String omittedClaim) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(USER_ID.toString())
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("USER_READ"));
        if (!"exp".equals(omittedClaim)) {
            builder.expiresAt(now.plus(Duration.ofMinutes(15)));
        }
        if (!"iat".equals(omittedClaim)) {
            builder.issuedAt(now);
        }
        return encode(builder.build());
    }

    private static String encode(JwtClaimsSet claims) {
        return ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static JwtDecoder decoder(String issuer, String audience, String secret) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(
                        new JwtProperties(issuer, audience, secret, Duration.ofMinutes(15)).hmacKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtAudienceValidator(audience),
                new RequiredClaimsValidator()));
        decoder.setClaimSetConverter(
                new AccessTokenAuthenticationConfiguration.PreserveAbsentIatClaimSetConverter());
        return decoder;
    }

    private void assertRejected(String token) {
        assertRejected(decoder, token);
    }

    private static void assertRejected(JwtDecoder decoder, String token) {
        Throwable thrown = catchThrowable(() -> decoder.decode(token));

        assertThat(thrown).isInstanceOf(JwtException.class);
        assertThat(thrown.getMessage()).doesNotContain(token);
    }
}
