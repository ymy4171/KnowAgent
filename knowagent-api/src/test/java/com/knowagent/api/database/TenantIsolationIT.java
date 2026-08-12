package com.knowagent.api.database;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.common.error.BusinessException;
import com.knowagent.common.error.ErrorCode;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.context.TenantContext;
import com.knowagent.security.domain.tenant.TenantStatus;
import com.knowagent.security.domain.user.UserStatus;
import com.knowagent.security.infrastructure.persistence.config.TenantContextTenantLineHandler;
import com.knowagent.security.infrastructure.persistence.entity.TenantPo;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import com.knowagent.security.infrastructure.persistence.mapper.TenantMapper;
import com.knowagent.security.infrastructure.persistence.mapper.UserMapper;
import com.knowagent.security.infrastructure.persistence.typehandler.PostgresUuidTypeHandler;
import com.knowagent.security.principal.TenantPrincipal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the tenant-line interceptor against a real PostgreSQL 16 container.
 *
 * <p>Runs only under the {@code docker-it} profile (Failsafe), like the other
 * database integration tests. Every case uses at least tenant-A and tenant-B data
 * and asserts fail-closed behavior when no {@link TenantContext} is present. The
 * container is shared across the class, so each test allocates fresh tenant UUIDs.
 */
@Testcontainers
class TenantIsolationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUpDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "tenant-isolation-it", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantContextTenantLineHandler()));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.addMapper(TenantMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(UserIdProbeMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    @AfterEach
    void isolateTenantContextPerTest() {
        TenantContext.clear();
    }

    @Test
    void ordinaryMybatisPlusQueryAutomaticallyAppendsTenantCondition() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID userA;
        UUID userB;
        try (SqlSession setup = sessionFactory.openSession(true)) {
            TenantMapper tenants = setup.getMapper(TenantMapper.class);
            insertTenant(tenants, tenantA, "iso-tenant-a");
            insertTenant(tenants, tenantB, "iso-tenant-b");

            TenantContext.set(principal(tenantA));
            userA = insertUser(setup.getMapper(UserMapper.class), "iso-user-a");
            TenantContext.set(principal(tenantB));
            userB = insertUser(setup.getMapper(UserMapper.class), "iso-user-b");
        }
        TenantContext.clear();

        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper users = session.getMapper(UserMapper.class);

            TenantContext.set(principal(tenantA));
            List<UserPo> forA = users.selectList(Wrappers.<UserPo>query());
            assertThat(forA).extracting(UserPo::getLoginName).containsExactly("iso-user-a");

            TenantContext.set(principal(tenantB));
            List<UserPo> forB = users.selectList(Wrappers.<UserPo>query());
            assertThat(forB).extracting(UserPo::getLoginName).containsExactly("iso-user-b");

            // A plain query never crosses the tenant boundary.
            assertThat(forB).hasSize(1);
            assertThat(userA).isNotEqualTo(userB);
        }
    }

    @Test
    void missingTenantContextFailsClosedForProtectedQuery() {
        UUID tenantA = UUID.randomUUID();
        try (SqlSession setup = sessionFactory.openSession(true)) {
            insertTenant(setup.getMapper(TenantMapper.class), tenantA, "failclosed-tenant");
            TenantContext.set(principal(tenantA));
            insertUser(setup.getMapper(UserMapper.class), "failclosed-user");
        }
        TenantContext.clear();

        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper users = session.getMapper(UserMapper.class);
            PersistenceException thrown = assertThrows(
                    PersistenceException.class,
                    () -> users.selectList(Wrappers.<UserPo>query()));
            assertTenantFailure(thrown);
        }
    }

    @Test
    void explicitTenantSqlCannotEnumerateOtherTenantRows() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID userB;
        try (SqlSession setup = sessionFactory.openSession(true)) {
            TenantMapper tenants = setup.getMapper(TenantMapper.class);
            insertTenant(tenants, tenantA, "enum-tenant-a");
            insertTenant(tenants, tenantB, "enum-tenant-b");
            TenantContext.set(principal(tenantA));
            UUID userA = insertUser(setup.getMapper(UserMapper.class), "enum-user-a");
            TenantContext.set(principal(tenantB));
            userB = insertUser(setup.getMapper(UserMapper.class), "enum-user-b");
            assertThat(userA).isNotNull();
        }
        TenantContext.clear();

        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper users = session.getMapper(UserMapper.class);
            UserIdProbeMapper probe = session.getMapper(UserIdProbeMapper.class);
            TenantContext.set(principal(tenantA));

            // Standard BaseMapper selectById is also tenant-scoped.
            assertThat(users.selectById(userB)).isNull();

            // Custom SQL that filters only by id is rewritten to add tenant_id,
            // so tenant-A cannot read tenant-B's row even with its exact id.
            assertThat(probe.selectByIdScoped(userB)).isNull();
            assertThat(probe.selectByIdScoped(tenantA)).isNull();
        }
    }

    @Test
    void insertWithoutPoTenantIsFilledFromContext() {
        UUID tenantA = UUID.randomUUID();
        try (SqlSession setup = sessionFactory.openSession(true)) {
            insertTenant(setup.getMapper(TenantMapper.class), tenantA, "fill-tenant-a");

            UserPo record = new UserPo();
            record.setId(UUID.randomUUID());
            // tenantId intentionally left null: the tenant line interceptor must
            // append the tenant_id column from TenantContext.
            record.setLoginName("filled-user");
            record.setDisplayName("Filled User");
            record.setPasswordHash("$test-only-hash$");
            record.setStatus(UserStatus.ACTIVE);
            record.setLoginFailedCount(0);
            record.setVersion(0L);

            TenantContext.set(principal(tenantA));
            UserMapper users = setup.getMapper(UserMapper.class);
            assertThat(users.insert(record)).isEqualTo(1);

            UserPo stored = users.selectById(record.getId());
            assertThat(stored).isNotNull();
            assertThat(stored.getTenantId()).isEqualTo(tenantA);
        }
    }

    @Test
    void insertWithoutTenantContextFailsClosed() {
        UUID tenantA = UUID.randomUUID();
        try (SqlSession setup = sessionFactory.openSession(true)) {
            insertTenant(setup.getMapper(TenantMapper.class), tenantA, "failinsert-tenant");

            UserPo record = new UserPo();
            record.setId(UUID.randomUUID());
            // No tenantId and no context: the interceptor must reject the write
            // instead of inserting a row with a null/ambiguous tenant.
            record.setLoginName("no-context-user");
            record.setDisplayName("No Context User");
            record.setPasswordHash("$test-only-hash$");
            record.setStatus(UserStatus.ACTIVE);
            record.setLoginFailedCount(0);
            record.setVersion(0L);

            PersistenceException thrown = assertThrows(
                    PersistenceException.class,
                    () -> setup.getMapper(UserMapper.class).insert(record));
            assertTenantFailure(thrown);
        }
    }

    /**
     * Boots the real Spring context so the tenant interceptor comes from
     * {@link com.knowagent.security.infrastructure.persistence.config.SecurityPersistenceConfiguration}
     * production wiring, then verifies that a tenant-scoped query through the
     * auto-configured {@link UserMapper} is actually rewritten with a tenant
     * condition. This guards against the hand-built session factory above silently
     * masking a missing plugin in the production configuration.
     */
    @Test
    void productionSpringBootContextEnforcesTenantIsolationOnUserMapper() throws SQLException {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR")) {
            UserMapper users = context.getBean(UserMapper.class);
            TenantMapper tenants = context.getBean(TenantMapper.class);

            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            insertTenant(tenants, tenantA, "prod-ctx-tenant-a");
            insertTenant(tenants, tenantB, "prod-ctx-tenant-b");

            TenantContext.set(principal(tenantA));
            UUID userA = insertUser(users, "prod-ctx-user-a");
            TenantContext.set(principal(tenantB));
            UUID userB = insertUser(users, "prod-ctx-user-b");
            TenantContext.clear();

            TenantContext.set(principal(tenantA));
            try {
                assertThat(users.selectById(userB)).isNull();
                assertThat(users.selectList(Wrappers.<UserPo>query()))
                        .extracting(UserPo::getLoginName)
                        .containsExactly("prod-ctx-user-a");
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * MyBatis wraps the {@link BusinessException} thrown by the tenant handler in a
     * {@link PersistenceException}; the root cause carries the fail-closed error code.
     */
    private static void assertTenantFailure(PersistenceException thrown) {
        BusinessException cause = (BusinessException) thrown.getCause();
        assertThat(cause.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static TenantPrincipal principal(UUID tenantId) {
        return new TenantPrincipal(TenantId.of(tenantId), UUID.randomUUID(), Set.of("ROLE_USER"));
    }

    private static void insertTenant(TenantMapper mapper, UUID id, String slug) {
        TenantPo record = new TenantPo();
        record.setId(id);
        record.setSlug(slug);
        record.setName("Tenant " + slug);
        record.setStatus(TenantStatus.ACTIVE);
        record.setVersion(0L);
        assertThat(mapper.insert(record)).isEqualTo(1);
    }

    private static UUID insertUser(UserMapper mapper, String loginName) {
        UserPo record = new UserPo();
        record.setId(UUID.randomUUID());
        record.setLoginName(loginName);
        record.setDisplayName("User " + loginName);
        record.setPasswordHash("$test-only-hash$");
        record.setStatus(UserStatus.ACTIVE);
        record.setLoginFailedCount(0);
        record.setVersion(0L);
        assertThat(mapper.insert(record)).isEqualTo(1);
        return record.getId();
    }

    /**
     * Custom annotation SQL that filters only by {@code id}. The tenant line
     * interceptor must rewrite it into {@code WHERE id = ? AND tenant_id = <ctx>}
     * even though the SQL text itself carries no tenant condition.
     */
    @Mapper
    interface UserIdProbeMapper {
        @Select("""
                SELECT id, tenant_id, department_id, login_name, display_name, email, phone_number,
                       avatar_object_key, password_hash, status, login_failed_count, last_failed_login_at,
                       login_locked_until, last_login_at, version, created_at, updated_at, deleted_at
                FROM users
                WHERE id = #{id}
                LIMIT 1
                """)
        UserPo selectByIdScoped(@Param("id") UUID id);
    }
}
