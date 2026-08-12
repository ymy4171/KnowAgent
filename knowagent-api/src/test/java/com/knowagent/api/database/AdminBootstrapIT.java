package com.knowagent.api.database;

import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.application.port.out.AdminBootstrapRepository;
import com.knowagent.security.application.port.out.PasswordHasher;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import com.knowagent.security.domain.role.Role;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.domain.tenant.Tenant;
import com.knowagent.security.domain.user.User;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisAdminBootstrapRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the developer-admin bootstrap against a real PostgreSQL 16 container and
 * the production Spring wiring.
 *
 * <p>Runs only under the {@code docker-it} profile (Failsafe). It verifies the four
 * acceptance criteria from the task: first execution creates the full tenant / ADMIN
 * role / admin user / binding, a second execution creates no duplicates, the password
 * is persisted only as an Argon2id hash (never the raw value), and a failing step
 * rolls the whole transaction back.
 *
 * <p>The bootstrap is triggered by calling {@link AdminBootstrap#initialize} on the
 * real service bean after the context boots with {@code bootstrap.enabled=false}
 * (the startup runner skips, leaving the service callable and the container state
 * deterministic).
 */
@Testcontainers
class AdminBootstrapIT {

    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    private static DataSource dataSource;

    @BeforeAll
    static void setUpDatabase() {
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
    }

    @Test
    void firstRunCreatesBootstrapDataAndSecondRunIsIdempotent() {
        try (ConfigurableApplicationContext context = context()) {
            AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
            PasswordHasher hasher = context.getBean(PasswordHasher.class);
            AdminBootstrapRequest request =
                    new AdminBootstrapRequest("acme", null, "admin@acme.test", null, RAW_PASSWORD);

            bootstrap.initialize(request);
            assertBootstrapDataOnce(request, hasher);

            bootstrap.initialize(request);
            assertBootstrapDataOnce(request, hasher);
        }
    }

    @Test
    void failedStepRollsBackTheWholeTransaction() {
        try (ConfigurableApplicationContext context = context(FailingAdminBootstrapConfiguration.class)) {
            AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
            AdminBootstrapRequest request =
                    new AdminBootstrapRequest("rollback-acme", null, "admin@rollback.test", null, RAW_PASSWORD);

            assertThatThrownBy(() -> bootstrap.initialize(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected bootstrap failure");

            // Scoped to the rollback tenant: the class-level container is shared, so
            // another test may already hold an ADMIN role for its own tenant.
            assertThat(count("SELECT count(*) FROM tenants WHERE slug = ?", "rollback-acme")).isZero();
            assertThat(count("""
                    SELECT count(*) FROM users u
                    JOIN tenants t ON u.tenant_id = t.id
                    WHERE t.slug = ? AND u.login_name = ?""", "rollback-acme", "admin@rollback.test")).isZero();
            assertThat(count("""
                    SELECT count(*) FROM roles r
                    JOIN tenants t ON r.tenant_id = t.id
                    WHERE t.slug = ? AND r.code = ?""", "rollback-acme", "ADMIN")).isZero();
        }
    }

    private void assertBootstrapDataOnce(AdminBootstrapRequest request, PasswordHasher hasher) {
        UUID tenantId = singleUuid("SELECT id FROM tenants WHERE slug = ?", request.tenantSlug());
        UUID userId = singleUuid(
                "SELECT id FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, request.adminLogin());
        UUID roleId = singleUuid(
                "SELECT id FROM roles WHERE tenant_id = ? AND code = ?", tenantId, "ADMIN");

        assertThat(count("SELECT count(*) FROM tenants WHERE slug = ?", request.tenantSlug())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, request.adminLogin())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM roles WHERE tenant_id = ? AND code = ?",
                tenantId, "ADMIN")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_roles WHERE tenant_id = ? AND user_id = ? AND role_id = ?",
                tenantId, userId, roleId)).isEqualTo(1);

        assertThat(singleBoolean("SELECT is_system FROM roles WHERE id = ?", roleId)).isTrue();
        String permissions = singleString("SELECT permissions::text FROM roles WHERE id = ?", roleId);
        assertThat(permissions).contains("USER_READ", "ROLE_WRITE", "TENANT_WRITE");

        String hash = singleString(
                "SELECT password_hash FROM users WHERE tenant_id = ? AND login_name = ?",
                tenantId, request.adminLogin());
        assertThat(hash).startsWith("$argon2id$").doesNotContain(RAW_PASSWORD);
        assertThat(hasher.matches(RAW_PASSWORD, hash)).isTrue();
        assertThat(hasher.matches("wrong-" + RAW_PASSWORD, hash)).isFalse();
    }

    private static ConfigurableApplicationContext context(Class<?>... extraSources) {
        return new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .sources(extraSources)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--bootstrap.enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
    }

    /**
     * Marks the decorated repository as the primary {@link AdminBootstrapRepository}
     * so the service uses it, while the real MyBatis adapter stays available as the
     * delegate. The decorator lets every write through except {@code insertRole},
     * which throws after the tenant insert has already happened inside the
     * transaction - proving the tenant write is rolled back with the failed run.
     *
     * <p>Deliberately a plain class with a {@code @Bean} method - not
     * {@code @Configuration}: {@code KnowAgentApiApplication} scans
     * {@code com.knowagent}, and the Failsafe runtime classpath includes these test
     * classes, so an ordinary {@code @Configuration} (which is meta-annotated
     * {@code @Component}) would be auto-registered in every context boot. The default
     * component-scan filter only matches {@code @Component}-annotated classes, so the
     * failing bean is registered only when this class is explicitly added via
     * {@code .sources(...)}, where Spring still processes its {@code @Bean} method.
     */
    static class FailingAdminBootstrapConfiguration {
        @Bean
        @Primary
        AdminBootstrapRepository failingAdminBootstrapRepository(MyBatisAdminBootstrapRepository delegate) {
            return new FailingAdminBootstrapRepository(delegate);
        }
    }

    private static final class FailingAdminBootstrapRepository implements AdminBootstrapRepository {
        private final AdminBootstrapRepository delegate;

        private FailingAdminBootstrapRepository(AdminBootstrapRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Tenant> findTenantBySlug(String slug) {
            return delegate.findTenantBySlug(slug);
        }

        @Override
        public Optional<Role> findRoleByTenantAndCode(TenantId tenantId, String code) {
            return delegate.findRoleByTenantAndCode(tenantId, code);
        }

        @Override
        public Optional<User> findUserByTenantAndLogin(TenantId tenantId, String loginName) {
            return delegate.findUserByTenantAndLogin(tenantId, loginName);
        }

        @Override
        public boolean existsUserRole(TenantId tenantId, UUID userId, UUID roleId) {
            return delegate.existsUserRole(tenantId, userId, roleId);
        }

        @Override
        public void insertTenant(Tenant tenant) {
            delegate.insertTenant(tenant);
        }

        @Override
        public void insertRole(Role role) {
            throw new RuntimeException("injected bootstrap failure");
        }

        @Override
        public void insertUser(User user) {
            delegate.insertUser(user);
        }

        @Override
        public void insertUserRole(UserRole userRole) {
            delegate.insertUserRole(userRole);
        }
    }

    private long count(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
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

    private boolean singleBoolean(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getBoolean(1);
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
