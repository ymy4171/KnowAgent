package com.knowagent.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Type-safe configuration for Access Token issuance and validation.
 *
 * <p>The signing key is deliberately the only secret here and it must come from an
 * environment variable or external config - {@code application.yml} only carries
 * empty {@code ${JWT_...:}} placeholders and never a real key. The value must be
 * base64-encoded and decode to at least 32 bytes (256 bits), the minimum key size
 * for HS256. The API fails fast at startup when any of {@code issuer},
 * {@code audience} or {@code secret} is missing, so a misconfigured instance can
 * never silently start without token validation.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String secret,
        Duration accessTokenTtl) {

    /** Minimum HS256 key size in bytes (256 bits). */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("jwt.issuer must be set (JWT_ISSUER)");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("jwt.audience must be set (JWT_AUDIENCE)");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("jwt.secret must be set (JWT_SECRET)");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("jwt.secret must be a base64-encoded key", e);
        }
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret must decode to at least " + MIN_SECRET_BYTES
                            + " bytes (256 bits) for HS256");
        }
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("jwt.access-token-ttl must be a positive duration");
        }
    }

    /** The decoded HMAC key used by both the encoder and the decoder. */
    public SecretKey hmacKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
    }

    /**
     * Never emits the signing key: the record's implicit toString would include the
     * full JWT_SECRET, which could leak into logs or exception messages if this
     * properties object is ever printed.
     */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer
                + ", audience=" + audience
                + ", secret=[REDACTED]"
                + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
