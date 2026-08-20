package com.knowagent.model.crypto;

/**
 * Encryption port for model-provider secrets (API keys and custom headers).
 *
 * <p>The domain and application layers depend only on this interface and on
 * {@link EncryptedSecret} - never on a concrete cipher or a JDK/provider crypto
 * type. Implementations must use an authenticated cipher (AES-256-GCM or
 * equivalent), use a fresh random nonce per encryption, and encode the ciphertext
 * plus key version in a parseable envelope so the key can be rotated without
 * re-encrypting every row.
 */
public interface SecretCipher {

    /** Encrypts a plaintext secret and returns its self-describing envelope. */
    EncryptedSecret encrypt(String plaintext);

    /** Decrypts an envelope back to plaintext; fails on an unknown version or tampered ciphertext. */
    String decrypt(EncryptedSecret secret);

    /** Whether a master key is available. When {@code false}, {@link #encrypt} must be rejected. */
    boolean isConfigured();
}
