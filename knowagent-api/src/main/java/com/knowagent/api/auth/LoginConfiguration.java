package com.knowagent.api.auth;

import com.knowagent.security.application.service.LoginPolicies;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the {@code auth.login.*} properties and exposes them to the security module
 * as a {@link LoginPolicies} value object. Kept in the web module (not the security
 * JAR) because {@code @ConfigurationProperties} binding belongs where the Spring
 * Boot application lives.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginProperties.class)
public class LoginConfiguration {

    @Bean
    LoginPolicies loginPolicies(LoginProperties properties) {
        return properties.toPolicies();
    }
}
