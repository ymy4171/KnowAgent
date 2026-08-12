package com.knowagent.security.infrastructure.crypto;

import com.knowagent.security.application.port.out.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Spring Security {@code Argon2PasswordEncoder} adapter for the
 * {@link PasswordHasher} port.
 *
 * <p>Uses the v5.8 default profile (salt 16 bytes, hash 32 bytes, 1 lane,
 * memory 64 MiB, 3 iterations) and emits an {@code $argon2id$} password string.
 * The encoder is a stateless service object, so a single shared
 * instance is safe. Bouncy Castle is the runtime provider required by
 * {@code Argon2PasswordEncoder} and is declared in {@code knowagent-security}'s POM.
 */
@Component
public class SpringSecurityArgon2PasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder encoder;

    public SpringSecurityArgon2PasswordHasher() {
        this(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    SpringSecurityArgon2PasswordHasher(Argon2PasswordEncoder encoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        Objects.requireNonNull(encodedPassword, "encodedPassword must not be null");
        return encoder.matches(rawPassword, encodedPassword);
    }
}
