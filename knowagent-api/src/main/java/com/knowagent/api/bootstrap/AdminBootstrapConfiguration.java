package com.knowagent.api.bootstrap;

import com.knowagent.security.application.service.AdminBootstrap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code bootstrap.*} properties and the startup runner that triggers
 * the admin initialization. The runner is deliberately created as a named bean here
 * (not component-scanned) so its dependencies are explicit and it can be disabled by
 * simply not setting {@code bootstrap.enabled}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapConfiguration {

    @Bean
    AdminBootstrapRunner adminBootstrapRunner(AdminBootstrapProperties properties, AdminBootstrap bootstrap) {
        return new AdminBootstrapRunner(properties, bootstrap);
    }
}
