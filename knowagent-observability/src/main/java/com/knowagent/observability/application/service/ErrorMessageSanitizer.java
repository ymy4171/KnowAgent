package com.knowagent.observability.application.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prepares an error message for durable storage in {@code tasks.error_message} /
 * {@code outbox_events.last_error}.
 *
 * <p>This is the single shared sanitization boundary for Task and Outbox error text:
 * both {@link com.knowagent.observability.outbox.OutboxEvent} (through
 * {@code failure}) and {@link com.knowagent.observability.application.port.out.TaskTransition}
 * redact, strip and truncate their error text through {@link #sanitize(String)} on
 * construction, so a raw message never reaches the persistence mappers. It redacts
 * common credential forms (Authorization/Bearer/Basic headers, api keys, client
 * secrets, passwords, JWTs), removes control characters (except tab and newline) and
 * truncates - a pathological exception string can neither leak a secret nor bloat or
 * corrupt the {@code text} column. Secrets and raw file content must also never be
 * passed into an error message by worker code in the first place; this sanitizer is
 * the last line of defence.
 */
public final class ErrorMessageSanitizer {

    /** Soft cap matching a conservative diagnostic length; kept short on purpose. */
    public static final int DEFAULT_MAX_LENGTH = 2000;

    /** Marker that replaces every redacted secret value. */
    public static final String REDACTED = "<redacted>";

    /**
     * Stable redaction rules. A {@code replacement} of {@code $1<redacted>} keeps the
     * credential field name while hiding its value; {@code <redacted>} alone replaces
     * a bare secret.
     */
    private static final List<RedactionRule> REDACTION_RULES = List.of(
            // Authorization header (Bearer/Basic): redact the whole header value.
            new RedactionRule(
                    Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)?\\s*)\\S+"),
                    "$1" + REDACTED),
            // Labeled credentials in key=value / key: value forms.
            new RedactionRule(
                    Pattern.compile("(?i)((?:x-api-key|api[_-]?key|client[_-]?secret|access[_-]?token"
                            + "|refresh[_-]?token|password|passwd|pwd|secret|token)\\s*[:=]\\s*)[^\\s,;}]+"),
                    "$1" + REDACTED),
            // OpenAI-style bare keys: sk- followed by at least 8 token characters.
            new RedactionRule(Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}\\b"), REDACTED),
            // JWTs: three dot-separated base64url segments.
            new RedactionRule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\b"),
                    REDACTED));

    private ErrorMessageSanitizer() {
    }

    /**
     * Redacts credentials, strips control characters (except tab and newline) and
     * truncates to {@code maxLength}. Returns {@code null} for {@code null} input.
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0");
        }
        String redacted = redact(raw);
        StringBuilder clean = new StringBuilder(redacted.length());
        for (int i = 0; i < redacted.length(); i++) {
            char c = redacted.charAt(i);
            if (c == '\t' || c == '\n' || (c >= 0x20 && c != 0x7F)) {
                clean.append(c);
            }
        }
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean.toString();
    }

    /** Truncates with the default cap. */
    public static String sanitize(String raw) {
        return sanitize(raw, DEFAULT_MAX_LENGTH);
    }

    private static String redact(String raw) {
        String result = raw;
        for (RedactionRule rule : REDACTION_RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private record RedactionRule(Pattern pattern, String replacement) {
    }
}
