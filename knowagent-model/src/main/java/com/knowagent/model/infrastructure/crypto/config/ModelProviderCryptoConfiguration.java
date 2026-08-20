package com.knowagent.model.infrastructure.crypto.config;

import com.knowagent.model.crypto.AesGcmSecretCipher;
import com.knowagent.model.crypto.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;

/** Shared API/Worker model-provider encryption configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProviderSecretProperties.class)
public class ModelProviderCryptoConfiguration {

    private static final int ACTIVE_KEY_VERSION = 1;

    @Bean
    public SecretCipher modelProviderSecretCipher(ModelProviderSecretProperties properties) {
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            return new AesGcmSecretCipher(Map.of(), ACTIVE_KEY_VERSION);
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.secretKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("model-provider.secret-key must be base64-encoded", exception);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "model-provider.secret-key must decode to 32 bytes (AES-256), got " + keyBytes.length);
        }
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        return new AesGcmSecretCipher(Map.of(ACTIVE_KEY_VERSION, key), ACTIVE_KEY_VERSION);
    }
}
