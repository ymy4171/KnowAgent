package com.knowagent.model.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretCipherTest {

    private static final SecretKey KEY_V1 = key("0123456789abcdef0123456789abcdef");
    private static final SecretKey KEY_V2 = key("fedcba9876543210fedcba9876543210");
    private static final String PLAINTEXT = "sk-proxy-0123456789abcdef-not-a-real-key";

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(Map.of(1, KEY_V1), 1);

        EncryptedSecret secret = cipher.encrypt(PLAINTEXT);

        assertThat(secret.envelope()).doesNotContain(PLAINTEXT);
        assertThat(secret.envelope()).doesNotContain("sk-proxy");
        assertThat(secret.keyVersion()).isEqualTo(1);
    }

    @Test
    void samePlaintextYieldsDifferentCiphertext() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(Map.of(1, KEY_V1), 1);

        assertThat(cipher.encrypt(PLAINTEXT).envelope())
                .isNotEqualTo(cipher.encrypt(PLAINTEXT).envelope());
    }

    @Test
    void roundTripDecryptsWithTheCorrectKey() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(Map.of(1, KEY_V1), 1);

        assertThat(cipher.decrypt(cipher.encrypt(PLAINTEXT))).isEqualTo(PLAINTEXT);
    }

    @Test
    void unknownKeyVersionFailsToDecrypt() {
        AesGcmSecretCipher writer = new AesGcmSecretCipher(Map.of(2, KEY_V2), 2);
        AesGcmSecretCipher reader = new AesGcmSecretCipher(Map.of(1, KEY_V1), 1);
        EncryptedSecret secret = writer.encrypt(PLAINTEXT);

        assertThat(secret.keyVersion()).isEqualTo(2);
        assertThatThrownBy(() -> reader.decrypt(secret))
                .isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("unknown secret key version");
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(Map.of(1, KEY_V1), 1);
        EncryptedSecret original = cipher.encrypt(PLAINTEXT);
        SecretEnvelope.Parsed parsed = SecretEnvelope.parse(original.envelope());
        parsed.ciphertext()[parsed.ciphertext().length - 1] ^= 0x01; // flip one bit of the tag
        EncryptedSecret tampered = new EncryptedSecret(
                SecretEnvelope.compose(parsed.version(), parsed.nonce(), parsed.ciphertext()), parsed.version());

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    void withoutAKeyEncryptionIsRejected() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(Map.of(), 1);

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(PLAINTEXT))
                .isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("no master key");
    }

    @Test
    void constructorRejectsNonAes256KeysAndMissingActiveVersion() {
        SecretKey aes128 = new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES");

        assertThatThrownBy(() -> new AesGcmSecretCipher(Map.of(1, aes128), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new AesGcmSecretCipher(Map.of(1, KEY_V1), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeKeyVersion");
    }

    private static SecretKey key(String material) {
        return new SecretKeySpec(material.getBytes(StandardCharsets.UTF_8), "AES");
    }
}
