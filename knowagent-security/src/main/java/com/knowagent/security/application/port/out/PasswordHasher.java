package com.knowagent.security.application.port.out;

/**
 * Application port for one-way password hashing.
 *
 * <p>The application layer never sees raw password values beyond encoding and
 * verification; callers must treat the result of {@link #encode} as the only
 * representation allowed to reach the database or logs. The concrete implementation
 * ({@code knowagent-security} infrastructure) uses Spring Security's
 * {@code Argon2PasswordEncoder} so the stored value is an Argon2id password string
 * as required by {@code docs/database-schema.md}.
 */
public interface PasswordHasher {

    /** Encodes a raw password into a salted one-way hash. Never returns the raw value. */
    String encode(CharSequence rawPassword);

    /** Verifies a raw password against a previously encoded hash. */
    boolean matches(CharSequence rawPassword, String encodedPassword);
}
