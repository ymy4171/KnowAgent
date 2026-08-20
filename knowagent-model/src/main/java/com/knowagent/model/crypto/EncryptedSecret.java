package com.knowagent.model.crypto;

import java.util.Objects;

/**
 * An encrypted secret: the self-describing envelope string plus the key version it
 * was encrypted with. The envelope carries its own version, and the redundant
 * {@code keyVersion} field is validated against it so a corrupt or mismatched
 * record is rejected at construction rather than surfacing later during decryption.
 *
 * <p>The {@link #toString()} never renders the envelope, so a logged or
 * exception-embedded value cannot leak the ciphertext.
 */
public record EncryptedSecret(String envelope, int keyVersion) {

    public EncryptedSecret {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (keyVersion <= 0) {
            throw new IllegalArgumentException("keyVersion must be > 0");
        }
        int parsed = SecretEnvelope.parse(envelope).version();
        if (parsed != keyVersion) {
            throw new IllegalArgumentException(
                    "envelope keyVersion " + parsed + " does not match " + keyVersion);
        }
    }

    @Override
    public String toString() {
        return "EncryptedSecret[keyVersion=" + keyVersion + "]";
    }
}
