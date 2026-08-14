package com.knowagent.security.application.service;

import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-way hashing and generation for opaque Refresh Tokens.
 *
 * <p>The database stores only {@code SHA-256(token)} as the {@code token_hash}
 * column; the raw token is generated with 32 bytes of CSPRNG entropy, returned to
 * the caller exactly once, and never logged or persisted. Shared by the login and
 * refresh flows so every token is hashed and generated identically.
 */
final class RefreshTokenHashes {

    private static final int TOKEN_BYTES = 32;

    private RefreshTokenHashes() {
    }

    /** SHA-256 of a raw token value, lowercase hex (the {@code token_hash} column). */
    static String hash(String rawValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", exception);
        }
    }

    /** A fresh 32-byte Base64url raw token value; never persisted in this form. */
    static String generateRaw() {
        byte[] random = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** Truncates a caller-supplied string (for example the User-Agent) to a column limit. */
    static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * Whether a unique-violation is exactly the one-child constraint. Only that
     * constraint maps to a replay rejection; any other constraint violation must be
     * surfaced rather than swallowed as a replay result.
     */
    static boolean isOneChildConstraint(DuplicateKeyException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("uq_refresh_tokens_one_child")) {
                return true;
            }
        }
        return false;
    }
}
