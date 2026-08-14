package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.api.security.JwtTestSupport;
import com.knowagent.security.context.TenantContext;
import it.contract.ProtectedProbeController;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the Access Token infrastructure through the real HTTP security chain.
 *
 * <p>Runs only under the {@code docker-it} profile (Failsafe). It boots the
 * production context against a PostgreSQL 16 container, registers the test-only
 * {@link ProtectedProbeController} as an additional source, and drives requests
 * through MockMvc so the OAuth2 Resource Server filter chain is applied. It proves
 * that a valid token reaches a protected endpoint, maps roles/permissions into
 * granted authorities, establishes and then clears {@link TenantContext}, that
 * missing, tampered, expired, wrong-issuer, wrong-audience and malformed-claim
 * tokens all fail with a stable JSON 401 that never contains the token value, and
 * that an authenticated-but-forbidden request yields the JSON 403 written by
 * {@link com.knowagent.api.config.JsonAccessDeniedHandler}. The management server's
 * own port is also probed over real HTTP to prove {@code /actuator/health} is
 * served there (the main port answering 404 would prove nothing about it).
 */
@Testcontainers
class AccessTokenSecurityIT {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    /** Free port the management server is told to bind, read back in the health test. */
    private static int managementPort;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    private static ConfigurableApplicationContext context;
    private static MockMvc mockMvc;
    private static JwtEncoder jwtEncoder;

    @BeforeAll
    static void bootContext() {
        managementPort = freePort();
        context = new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .sources(ProtectedProbeController.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        // The management server runs on its own port, which
                        // managementPortServesActuatorHealth probes over real HTTP.
                        // Redis is pointed at a dead URL for these tests, so its
                        // health indicator is disabled to keep /actuator/health UP.
                        "--management.server.port=" + managementPort,
                        "--management.health.redis.enabled=false",
                        "--bootstrap.enabled=false",
                        "--jwt.issuer=" + ISSUER,
                        "--jwt.audience=" + AUDIENCE,
                        "--jwt.secret=" + JWT_SECRET,
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        // webAppContextSetup does not auto-register the security filter chain in a
        // programmatically booted context, so the chain is added explicitly.
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        jwtEncoder = context.getBean(JwtEncoder.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousAccessToProtectedResourceReturnsJson401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void publicRoutesRemainAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/system/info")).andExpect(status().isOk());
        // Actuator runs on its own management port (management.server.port), so the
        // main port answers 404 here - but critically NOT 401, which proves the
        // /actuator/health/** permitAll rule lets the request past the security chain.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        // permitAll, not authenticated: the three auth controllers only accept POST,
        // so a GET yields 405 (method not allowed) - never 401, proving each route is
        // not protected. A GET to an unmapped path yields 404 (also never 401).
        mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/auth/refresh")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/auth/logout")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void validTokenReachesProtectedEndpointMapsAuthoritiesAndClearsTenantContext() throws Exception {
        String token = validToken();
        assertThat(TenantContext.isSet()).isFalse();

        MvcResult result = mockMvc.perform(
                        get("/api/v1/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("userId").asText()).isEqualTo(USER_ID.toString());
        assertThat(body.path("tenantId").asText()).isEqualTo(TENANT_ID.toString());
        assertThat(body.path("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(body.path("authorities").toString())
                .contains("ROLE_ADMIN", "USER_READ", "TENANT_WRITE");
        assertThat(body.path("tenantContextPresent").asBoolean()).isTrue();

        assertThat(TenantContext.isSet()).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
    }

    @Test
    void tamperedTokenIsRejectedWithJson401() throws Exception {
        String token = validToken();
        // Flip a real signature byte, not the final Base64URL character (whose
        // unused padding bits may decode to the same signature).
        String tampered = JwtTestSupport.tamperSignature(token);
        assertRejected(tampered);
    }

    @Test
    void expiredTokenIsRejectedWithJson401() throws Exception {
        assertRejected(token(builder -> {
            // An expired token still needs exp after iat; both sit in the past.
            Instant past = Instant.now().minus(Duration.ofMinutes(30));
            builder.issuedAt(past);
            builder.expiresAt(past.plus(Duration.ofMinutes(15)));
        }));
    }

    @Test
    void wrongIssuerTokenIsRejectedWithJson401() throws Exception {
        assertRejected(token(builder -> builder.issuer("https://attacker.test")));
    }

    @Test
    void wrongAudienceTokenIsRejectedWithJson401() throws Exception {
        assertRejected(token(builder -> builder.audience(List.of("another-service"))));
    }

    @Test
    void tokenWithoutTenantIdIsRejectedWithJson401() throws Exception {
        assertRejected(tokenWithoutClaim("tenant_id"));
    }

    @Test
    void tokenWithMalformedTenantIdIsRejectedWithJson401() throws Exception {
        assertRejected(token(builder -> builder.claim("tenant_id", "not-a-uuid")));
    }

    @Test
    void tokenWithoutRolesIsRejectedWithJson401() throws Exception {
        assertRejected(tokenWithoutClaim("roles"));
    }

    @Test
    void tokenWithoutExpiryIsRejectedWithJson401() throws Exception {
        // A validly signed token with no exp would never expire; presence of the
        // claim is enforced, not just its value when it happens to be present.
        assertRejected(tokenWithoutClaim("exp"));
    }

    @Test
    void tokenWithoutIssuedAtIsRejectedWithJson401() throws Exception {
        assertRejected(tokenWithoutClaim("iat"));
    }

    @Test
    void managementPortServesActuatorHealth() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
                .contains("json");
        JsonNode body = OBJECT_MAPPER.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }

    @Test
    void adminTokenReachesAdminOnlyEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/probe/admin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedRequestLackingAuthorityReturnsJson403() throws Exception {
        String analystToken = token(builder -> builder.claim("roles", List.of("ANALYST")));
        MvcResult result = mockMvc.perform(
                        get("/api/v1/probe/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    private String validToken() {
        return token(builder -> { });
    }

    private String token(Consumer<JwtClaimsSet.Builder> mutation) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT_ID.toString())
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("USER_READ", "TENANT_WRITE"));
        mutation.accept(builder);
        return encode(builder.build());
    }

    private String tokenWithoutClaim(String omittedClaim) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(USER_ID.toString())
                .id(UUID.randomUUID().toString());
        if (!"exp".equals(omittedClaim)) {
            builder.expiresAt(now.plus(Duration.ofMinutes(15)));
        }
        if (!"iat".equals(omittedClaim)) {
            builder.issuedAt(now);
        }
        if (!"tenant_id".equals(omittedClaim)) {
            builder.claim("tenant_id", TENANT_ID.toString());
        }
        if (!"roles".equals(omittedClaim)) {
            builder.claim("roles", List.of("ADMIN"));
        }
        if (!"permissions".equals(omittedClaim)) {
            builder.claim("permissions", List.of("USER_READ", "TENANT_WRITE"));
        }
        return encode(builder.build());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port available for the management server", e);
        }
    }

    private String encode(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private void assertRejected(String token) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                // RFC 6750: token errors carry a detailed challenge like
                // 'Bearer error="invalid_token", error_description="..."'.
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
    }
}
