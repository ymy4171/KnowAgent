package com.knowagent.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityBootstrapConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // Run after authentication (principal is resolved) but before the
                // authorization decision: tenant-aware authorization can read
                // TenantContext, and a rejected request still passes through the
                // filter's finally cleanup before the 403 response.
                .addFilterBefore(new TenantContextFilter(), AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/api/v1/system/info").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
