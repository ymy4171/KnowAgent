package com.knowagent.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import java.util.Base64;

/**
 * Test helpers for building JWT infrastructure with the genuine Spring Security
 * 6.5.x API.
 *
 * <p>6.5 removed {@code NimbusJwtEncoder.withSecretKey(SecretKey)}; the encoder is
 * now constructed from a Nimbus {@link JWKSource}. These helpers mirror how Spring
 * Security's own tests wire a symmetric HS256 key, so the production bean and the
 * unit tests exercise the same construction.
 */
public final class JwtTestSupport {

    private JwtTestSupport() {
    }

    static JwtEncoder secretKeyEncoder(SecretKey key) {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(key)
                .algorithm(JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Mutates a JWT's signature by flipping one real byte of the decoded HMAC
     * output, then re-encoding it.
     *
     * <p>Editing the final Base64URL character of a token is not guaranteed to
     * change the decoded signature: that character can carry unused padding bits,
     * so the token may still decode to identical signature bytes and pass
     * validation. Flipping a decoded byte always changes the signature, so the
     * result is deterministically rejected by the signature check.
     */
    public static String tamperSignature(String token) {
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalArgumentException("not a signed JWT: " + token);
        }
        byte[] signature = Base64.getUrlDecoder().decode(token.substring(lastDot + 1));
        if (signature.length == 0) {
            throw new IllegalArgumentException("JWT has an empty signature");
        }
        signature[signature.length - 1] ^= 0x01;
        String tamperedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        return token.substring(0, lastDot + 1) + tamperedSignature;
    }
}
