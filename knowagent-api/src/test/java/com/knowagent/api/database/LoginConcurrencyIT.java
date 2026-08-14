package com.knowagent.api.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the login failure path cannot exhaust the database connection pool.
 *
 * <p>Runs only under the {@code docker-it} profile (Failsafe). It boots the
 * production context against PostgreSQL 16 with a deliberately tiny Hikari pool of
 * 4, then fires 4 concurrent wrong-password logins. With a transactional
 * {@code login()} plus a nested {@code REQUIRES_NEW} failure recorder every request
 * would hold one connection and wait on a second, deadlocking the pool and timing
 * out; since the login flow is non-transactional and each write runs in its own
 * single-connection transaction, all four requests complete with a stable 401 (or
 * 403 once the run itself locks the account) and never a 500.
 */
@Testcontainers
class LoginConcurrencyIT {

    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final int POOL_SIZE = 4;
    private static final int CONCURRENT_ATTEMPTS = 4;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    private static ConfigurableApplicationContext context;
    private static MockMvc mockMvc;
    private static DataSource dataSource;

    @BeforeAll
    static void bootContext() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        context = new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--bootstrap.enabled=false",
                        "--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                        "--auth.login.max-failed-attempts=3",
                        "--auth.login.lock-duration=15m",
                        "--jwt.issuer=https://knowagent.test",
                        "--jwt.audience=knowagent-api",
                        "--jwt.secret=" + Base64.getEncoder().encodeToString(
                                "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8)),
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("pool4", null, "admin@pool4.test", null, RAW_PASSWORD));
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void fourConcurrentWrongPasswordsCompleteAgainstAPoolOfFour() throws Exception {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", "pool4");
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?", tenantId, "admin@pool4.test");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String wrongBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "tenantSlug", "pool4",
                "loginName", "admin@pool4.test",
                "password", "wrong-" + RAW_PASSWORD));
        for (int index = 0; index < CONCURRENT_ATTEMPTS; index++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongBody))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        // Every request must complete: a pool-exhaustion deadlock would block the
        // Hikari connection acquisition and time out here instead of returning.
        long unauthorized = 0;
        for (Future<Integer> future : results) {
            int status = future.get(20, TimeUnit.SECONDS);
            if (status == 401) {
                unauthorized++;
            } else {
                assertThat(status).isEqualTo(403);
            }
        }
        pool.shutdown();

        // None lost a count, and the concurrent run itself reached the lock.
        int failed = singleInt("SELECT login_failed_count FROM users WHERE id = ?", userId);
        assertThat(failed).isEqualTo((int) unauthorized);
        assertThat(failed).isGreaterThanOrEqualTo(3);
        assertThat(singleString("SELECT status FROM users WHERE id = ?", userId)).isEqualTo("LOCKED");

        // The lock holds: even the correct password is now rejected.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "tenantSlug", "pool4",
                                "loginName", "admin@pool4.test",
                                "password", RAW_PASSWORD))))
                .andExpect(status().isForbidden());
    }

    private int singleInt(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String singleString(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID singleUuid(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }
}
