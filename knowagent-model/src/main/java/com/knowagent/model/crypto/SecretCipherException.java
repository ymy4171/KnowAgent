package com.knowagent.model.crypto;

/**
 * Raised when a secret cannot be encrypted or decrypted (missing master key,
 * unknown key version, tampered ciphertext). The message never contains the
 * plaintext, the envelope or any key material; callers map it to a stable error.
 */
public final class SecretCipherException extends RuntimeException {

    public SecretCipherException(String message) {
        super(message);
    }

    public SecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
