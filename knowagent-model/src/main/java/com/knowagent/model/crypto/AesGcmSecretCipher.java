package com.knowagent.model.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;

/**
 * {@link SecretCipher} built on the JDK's AES-256-GCM.
 *
 * <p>Every encryption uses a fresh 12-byte random nonce and a 128-bit GCM tag, so
 * the same plaintext never produces the same envelope. Keys are held by version so
 * an old envelope can still be decrypted after rotation; the {@code activeKeyVersion}
 * is what new encryptions use. A tampered ciphertext or an unknown key version makes
 * {@link #decrypt} throw {@link SecretCipherException} - the ciphertext never falls
 * back to a weaker mode or to plaintext.
 */
public final class AesGcmSecretCipher implements SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<Integer, SecretKey> keysByVersion;
    private final int activeKeyVersion;
    private final SecureRandom secureRandom;

    /**
     * @param keysByVersion    key version to AES-256 key; may be empty (nothing encrypted)
     * @param activeKeyVersion version used for new encryptions (ignored when the map is empty)
     */
    public AesGcmSecretCipher(Map<Integer, SecretKey> keysByVersion, int activeKeyVersion) {
        Objects.requireNonNull(keysByVersion, "keysByVersion must not be null");
        if (activeKeyVersion <= 0) {
            throw new IllegalArgumentException("activeKeyVersion must be > 0");
        }
        keysByVersion.forEach(AesGcmSecretCipher::validateAes256Key);
        if (!keysByVersion.isEmpty() && !keysByVersion.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException("activeKeyVersion must identify a configured key");
        }
        this.keysByVersion = Map.copyOf(keysByVersion);
        this.activeKeyVersion = activeKeyVersion;
        this.secureRandom = new SecureRandom();
    }

    @Override
    public boolean isConfigured() {
        return !keysByVersion.isEmpty();
    }

    @Override
    public EncryptedSecret encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        SecretKey key = keysByVersion.get(activeKeyVersion);
        if (key == null) {
            throw new SecretCipherException("secret encryption is unavailable: no master key is configured");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new EncryptedSecret(SecretEnvelope.compose(activeKeyVersion, nonce, ciphertext), activeKeyVersion);
        } catch (GeneralSecurityException exception) {
            throw new SecretCipherException("unable to encrypt secret", exception);
        }
    }

    @Override
    public String decrypt(EncryptedSecret secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        SecretEnvelope.Parsed parsed = SecretEnvelope.parse(secret.envelope());
        SecretKey key = keysByVersion.get(parsed.version());
        if (key == null) {
            throw new SecretCipherException("unknown secret key version " + parsed.version());
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, parsed.nonce()));
            byte[] plaintext = cipher.doFinal(parsed.ciphertext());
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new SecretCipherException("secret ciphertext failed authentication", exception);
        } catch (GeneralSecurityException exception) {
            throw new SecretCipherException("unable to decrypt secret", exception);
        }
    }

    private static void validateAes256Key(Integer version, SecretKey key) {
        if (version == null || version <= 0) {
            throw new IllegalArgumentException("secret key versions must be > 0");
        }
        if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("secret keys must use AES");
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null || encoded.length != 32) {
            throw new IllegalArgumentException("secret keys must contain exactly 32 bytes (AES-256)");
        }
    }
}
