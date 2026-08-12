package com.knowagent.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Map;

/**
 * Writes the API's JSON 401 response instead of Spring Security's default HTML.
 *
 * <p>The body uses the same {@code errorCode}/{@code message} shape as a
 * {@link com.knowagent.common.error.BusinessException} response. The
 * {@code WWW-Authenticate} challenge is a bare {@code Bearer} per RFC 6750 and
 * never carries the rejected token or an error description derived from it.
 */
public final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "errorCode", "AUTHENTICATION_REQUIRED",
                "message", "Authentication is required to access this resource."));
    }
}
