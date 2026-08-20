package com.knowagent.observability.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorMessageSanitizerTest {

    @Test
    void nullStaysNull() {
        assertThat(ErrorMessageSanitizer.sanitize(null)).isNull();
    }

    @Test
    void controlCharactersAreStrippedButTabAndNewlineSurvive() {
        String raw = "line one\twith a tab\n" + "null\000byte and \033 escape and DEL\177";
        String cleaned = ErrorMessageSanitizer.sanitize(raw, 10_000);

        assertThat(cleaned).isEqualTo("line one\twith a tab\nnullbyte and  escape and DEL");
    }

    @Test
    void truncatesToMaxLength() {
        String raw = "abcdefghij";
        assertThat(ErrorMessageSanitizer.sanitize(raw, 4)).isEqualTo("abcd");
    }

    @Test
    void truncationCountsCharactersAfterStripping() {
        // NUL and SOH (0x01) are removed, then the remaining 8 chars truncated to 5.
        String raw = "ab\000cd\001efgh";
        assertThat(ErrorMessageSanitizer.sanitize(raw, 5)).isEqualTo("abcde");
    }

    @Test
    void defaultCapIsBounded() {
        String longMessage = "x".repeat(10_000);
        String sanitized = ErrorMessageSanitizer.sanitize(longMessage);
        assertThat(sanitized).hasSize(ErrorMessageSanitizer.DEFAULT_MAX_LENGTH);
    }

    @Test
    void rejectsNegativeMaxLength() {
        assertThatThrownBy(() -> ErrorMessageSanitizer.sanitize("x", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactsLabeledCredentialsKeepingTheFieldName() {
        String raw = "request failed: api_key=sk-abc123def456 client_secret=shh password=hunter2 secret: value";
        String cleaned = ErrorMessageSanitizer.sanitize(raw, 10_000);

        assertThat(cleaned)
                .doesNotContain("sk-abc123def456")
                .doesNotContain("shh")
                .doesNotContain("hunter2")
                .doesNotContain("value")
                .contains("api_key=<redacted>")
                .contains("client_secret=<redacted>")
                .contains("password=<redacted>")
                .contains("secret: <redacted>");
    }

    @Test
    void redactsAuthorizationBearerTokensAndBareKeys() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcDEF123";
        String raw = "Authorization: Bearer " + jwt + " and sk-verysecretkey12345";
        String cleaned = ErrorMessageSanitizer.sanitize(raw, 10_000);

        assertThat(cleaned)
                .doesNotContain(jwt)
                .doesNotContain("sk-verysecretkey12345")
                .contains("Authorization: Bearer <redacted>")
                .contains("and <redacted>");
    }

    @Test
    void redactsBareJwtTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcDEF123";
        String cleaned = ErrorMessageSanitizer.sanitize("callback rejected token " + jwt, 10_000);

        assertThat(cleaned).doesNotContain(jwt).contains("token <redacted>");
    }

    @Test
    void redactionIsStableAndIdempotent() {
        String raw = "api_key=sk-abc123def456 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sig";
        String once = ErrorMessageSanitizer.sanitize(raw, 10_000);
        String twice = ErrorMessageSanitizer.sanitize(once, 10_000);

        assertThat(twice).isEqualTo(once);
    }
}
