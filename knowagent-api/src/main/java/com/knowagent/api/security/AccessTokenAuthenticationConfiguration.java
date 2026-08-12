package com.knowagent.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.util.Collections;
import java.util.Map;

/**
 * Wires Spring Security's JOSE (Nimbus) encoder/decoder and the JWT-to-principal
 * converter.
 *
 * <p>Both sides use the same HS256 key from {@link JwtProperties}. The decoder
 * validates, in order: the signature, the standard timestamps (exp/nbf/iat) and
 * issuer, the {@code aud} audience, and the presence of the Access Token contract
 * claims via {@link RequiredClaimsValidator}. The converter bean is consumed by
 * {@code SecurityBootstrapConfiguration} to plug into the OAuth2 Resource Server.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class AccessTokenAuthenticationConfiguration {

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        // Spring Security 6.5 dropped NimbusJwtEncoder.withSecretKey(SecretKey); the
        // encoder now takes a JWKSource. Expose the configured HS256 key as a single
        // symmetric JWK selected whenever the header asks for the HS256 algorithm.
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(properties.hmacKey())
                .algorithm(JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.hmacKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtAudienceValidator(properties.audience()),
                new RequiredClaimsValidator()));
        // MappedJwtClaimSetConverter auto-synthesises iat = exp - 1s when iat is
        // absent, which makes RequiredClaimsValidator unable to detect missing iat.
        // Replace it with a converter that preserves all default type conversions
        // but skips the auto-iat behaviour so a token without iat stays without iat.
        decoder.setClaimSetConverter(new PreserveAbsentIatClaimSetConverter());
        return decoder;
    }

    /**
     * Delegates to {@link MappedJwtClaimSetConverter#withDefaults(Map)} for all
     * standard type conversions (Unix timestamp → Instant, string → URL for iss,
     * etc.) but removes the auto-synthesised {@code iat} claim when it was absent
     * from the raw JWT payload.
     *
     * <p>Spring Security's out-of-the-box converter writes {@code iat = exp - 1s}
     * whenever {@code exp} is present but {@code iat} is not. That silently repairs
     * malformed tokens and prevents {@link RequiredClaimsValidator} from rejecting
     * them. This wrapper restores the missing-iat condition so the validator can act.
     */
    static final class PreserveAbsentIatClaimSetConverter implements Converter<Map<String, Object>, Map<String, Object>> {

        private final Converter<Map<String, Object>, Map<String, Object>> delegate =
                MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());

        @Override
        public Map<String, Object> convert(Map<String, Object> source) {
            boolean hadIat = source.containsKey("iat");
            Map<String, Object> converted = delegate.convert(source);
            if (!hadIat) {
                converted.remove("iat");
            }
            return converted;
        }
    }

    @Bean
    Converter<Jwt, JwtTenantAuthenticationToken> jwtToTenantAuthenticationConverter() {
        return new JwtToTenantAuthenticationConverter();
    }
}
