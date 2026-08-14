package com.knowagent.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.security.JwtTenantAuthenticationToken;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityBootstrapConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint,
            Converter<Jwt, JwtTenantAuthenticationToken> jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // Run after authentication (principal is resolved) but before the
                // authorization decision: tenant-aware authorization can read
                // TenantContext, and a rejected request still passes through the
                // filter's finally cleanup before the 403 response.
                .addFilterBefore(new TenantContextFilter(), AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health/**",
                                "/api/v1/system/info",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .anyRequest().authenticated())
                // Access tokens are validated by the OAuth2 Resource Server using
                // Spring Security's own JOSE decoder and then converted into a
                // JwtTenantAuthenticationToken whose principal is a TenantPrincipal.
                // The entry point is set here (not just on exceptionHandling) so
                // that BearerTokenAuthenticationFilter answers token failures with
                // the API's JSON 401 instead of the default empty-body 401.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint)
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)));
        return http.build();
    }

    @Bean
    JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper);
    }

    /**
     * Suppresses Spring Boot's auto-generated in-memory user and the development
     * password it would log at startup: the API only authenticates Access Tokens,
     * so there is deliberately no password-based default account.
     */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }
}
