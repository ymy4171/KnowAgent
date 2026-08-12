package com.knowagent.api.security;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
            "property-test-secret-0123456789abcdefghi".getBytes(StandardCharsets.UTF_8));

    @Test
    void validConfigurationExposesTheHmacKey() {
        JwtProperties properties =
                new JwtProperties("https://knowagent.test", "knowagent-api", TEST_SECRET, Duration.ofMinutes(15));

        SecretKey key = properties.hmacKey();

        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    void toStringRedactsTheSecret() {
        JwtProperties properties =
                new JwtProperties("https://knowagent.test", "knowagent-api", TEST_SECRET, Duration.ofMinutes(15));

        assertThat(properties.toString())
                .contains("secret=[REDACTED]")
                .contains("issuer=https://knowagent.test")
                .doesNotContain(TEST_SECRET);
    }

    @Test
    void blankIssuerAudienceOrSecretIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties("", "knowagent-api", TEST_SECRET, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.issuer");
        assertThatThrownBy(() ->
                new JwtProperties("https://knowagent.test", " ", TEST_SECRET, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.audience");
        assertThatThrownBy(() ->
                new JwtProperties("https://knowagent.test", "knowagent-api", "  ", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void secretShorterThan32BytesIsRejected() {
        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() ->
                new JwtProperties("https://knowagent.test", "knowagent-api", shortSecret, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void nonBase64SecretIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties("https://knowagent.test", "knowagent-api", "not-base64!!", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void nonPositiveTtlIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties("https://knowagent.test", "knowagent-api", TEST_SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-token-ttl");
        assertThatThrownBy(() ->
                new JwtProperties("https://knowagent.test", "knowagent-api", TEST_SECRET, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-token-ttl");
    }
}
