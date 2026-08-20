package com.knowagent.model.infrastructure.crypto.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the model-provider master key without ever rendering it in diagnostics.
 */
@ConfigurationProperties(prefix = "model-provider")
public record ModelProviderSecretProperties(String secretKey) {

    @Override
    public String toString() {
        return "ModelProviderSecretProperties[secretKey=[REDACTED]]";
    }
}
