package com.knowagent.model.infrastructure.crypto.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderSecretPropertiesTest {

    @Test
    void toStringRedactsTheMasterKey() {
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThat(new ModelProviderSecretProperties(secret).toString())
                .contains("[REDACTED]")
                .doesNotContain(secret);
    }

    @Test
    void configurationRejectsMalformedAndWrongLengthKeys() {
        ModelProviderCryptoConfiguration configuration = new ModelProviderCryptoConfiguration();

        assertThatThrownBy(() -> configuration.modelProviderSecretCipher(
                new ModelProviderSecretProperties("not-base64")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> configuration.modelProviderSecretCipher(
                new ModelProviderSecretProperties(Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
