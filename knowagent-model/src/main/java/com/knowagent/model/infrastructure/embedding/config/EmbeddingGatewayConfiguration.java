package com.knowagent.model.infrastructure.embedding.config;

import com.knowagent.model.application.port.out.ModelProviderRepository;
import com.knowagent.model.crypto.SecretCipher;
import com.knowagent.model.embedding.EmbeddingGateway;
import com.knowagent.model.infrastructure.embedding.OpenAiCompatibleEmbeddingGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the single {@link EmbeddingGateway} bean. Exactly one adapter bean exists
 * regardless of how many model providers are configured; providers are resolved per
 * call from {@link ModelProviderRepository}, and per-provider clients live only in the
 * bounded internal cache. {@code MeterRegistry} is optional: without actuator the
 * gateway records no metrics instead of failing to start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingGatewayConfiguration {

    @Bean
    public EmbeddingGateway embeddingGateway(ModelProviderRepository providers,
                                             SecretCipher secretCipher,
                                             EmbeddingProperties properties,
                                             ObjectProvider<MeterRegistry> meterRegistry) {
        return new OpenAiCompatibleEmbeddingGateway(
                providers, secretCipher, properties, meterRegistry.getIfUnique());
    }
}
