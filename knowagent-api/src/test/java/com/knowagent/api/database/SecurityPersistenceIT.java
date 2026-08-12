package com.knowagent.api.database;

import com.knowagent.api.KnowAgentApiApplication;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.security.domain.role.RoleStatus;
import com.knowagent.security.domain.role.UserRole;
import com.knowagent.security.domain.tenant.TenantStatus;
import com.knowagent.security.domain.token.RefreshTokenStatus;
import com.knowagent.security.domain.user.UserStatus;
import com.knowagent.security.infrastructure.persistence.entity.RefreshTokenPo;
import com.knowagent.security.infrastructure.persistence.entity.RolePo;
import com.knowagent.security.infrastructure.persistence.entity.TenantPo;
import com.knowagent.security.infrastructure.persistence.entity.UserPo;
import com.knowagent.security.infrastructure.persistence.mapper.RefreshTokenMapper;
import com.knowagent.security.infrastructure.persistence.mapper.RoleMapper;
import com.knowagent.security.infrastructure.persistence.mapper.TenantMapper;
import com.knowagent.security.infrastructure.persistence.mapper.UserMapper;
import com.knowagent.security.infrastructure.persistence.mapper.UserRoleMapper;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisRefreshTokenStore;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisRoleRepository;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisTenantRepository;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisUserRepository;
import com.knowagent.security.infrastructure.persistence.repository.MyBatisUserRoleStore;
import com.knowagent.security.infrastructure.persistence.typehandler.PostgresUuidTypeHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class SecurityPersistenceIT {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OffsetDateTime ISSUED_AT = OffsetDateTime.of(
            2026, 8, 11, 8, 0, 0, 0, ZoneOffset.ofHours(8));

    /** Test-only HS256 key; the mandatory JWT beans require it at context boot. */
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("integration_only");

    private static PGSimpleDataSource dataSource;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUpDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "security-persistence-it", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.addMapper(TenantMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(RoleMapper.class);
        configuration.addMapper(UserRoleMapper.class);
        configuration.addMapper(RefreshTokenMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void tenantAndUserRepositoriesEnforcePreAuthenticationLookupRules() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            TenantMapper tenants = session.getMapper(TenantMapper.class);
            UserMapper users = session.getMapper(UserMapper.class);
            TenantPo active = insertTenant(tenants, "active-tenant", TenantStatus.ACTIVE);
            TenantPo suspended = insertTenant(tenants, "suspended-tenant", TenantStatus.SUSPENDED);
            TenantPo deleted = insertTenant(tenants, "deleted-tenant", TenantStatus.ACTIVE);
            deleted.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            assertThat(tenants.updateById(deleted)).isEqualTo(1);

            UserPo userA = insertUser(users, active.getId(), "shared-login");
            TenantPo tenantB = insertTenant(tenants, "tenant-b", TenantStatus.ACTIVE);
            UserPo userB = insertUser(users, tenantB.getId(), "shared-login");
            UserPo deletedUser = insertUser(users, active.getId(), "deleted-user");
            deletedUser.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            assertThat(users.updateById(deletedUser)).isEqualTo(1);

            MyBatisTenantRepository tenantRepository = new MyBatisTenantRepository(tenants);
            MyBatisUserRepository userRepository = new MyBatisUserRepository(users);

            assertThat(tenantRepository.findActiveBySlug(active.getSlug())).get()
                    .extracting(tenant -> tenant.id().value()).isEqualTo(active.getId());
            assertThat(tenantRepository.findActiveBySlug(suspended.getSlug())).isEmpty();
            assertThat(tenantRepository.findActiveBySlug(deleted.getSlug())).isEmpty();
            assertThat(userRepository.findByTenantAndLoginName(TenantId.of(active.getId()), "shared-login")).get()
                    .extracting(user -> user.id()).isEqualTo(userA.getId());
            assertThat(userRepository.findByTenantAndLoginName(TenantId.of(tenantB.getId()), "shared-login")).get()
                    .extracting(user -> user.id()).isEqualTo(userB.getId());
            assertThat(userRepository.findByTenantAndLoginName(TenantId.of(active.getId()), "deleted-user"))
                    .isEmpty();
            assertThat(userRepository.findByTenantAndLoginName(TenantId.of(tenantB.getId()), "deleted-user"))
                    .isEmpty();
        }
    }

    @Test
    void effectiveRolesFilterStatusDeletionExpiryAndCrossTenantUserIds() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            TenantMapper tenants = session.getMapper(TenantMapper.class);
            UserMapper users = session.getMapper(UserMapper.class);
            RoleMapper roles = session.getMapper(RoleMapper.class);
            UserRoleMapper assignments = session.getMapper(UserRoleMapper.class);
            MyBatisUserRoleStore assignmentStore = new MyBatisUserRoleStore(assignments);
            TenantPo tenantA = insertTenant(tenants, "roles-tenant-a", TenantStatus.ACTIVE);
            TenantPo tenantB = insertTenant(tenants, "roles-tenant-b", TenantStatus.ACTIVE);
            UserPo userA = insertUser(users, tenantA.getId(), "role-user-a");
            UserPo userB = insertUser(users, tenantB.getId(), "role-user-b");

            RolePo active = insertRole(roles, tenantA.getId(), "ACTIVE_ROLE", RoleStatus.ACTIVE,
                    Set.of("USER_READ", "USER_ADMIN"));
            RolePo disabled = insertRole(roles, tenantA.getId(), "DISABLED_ROLE", RoleStatus.DISABLED,
                    Set.of("DISABLED_PERMISSION"));
            RolePo expired = insertRole(roles, tenantA.getId(), "EXPIRED_ROLE", RoleStatus.ACTIVE,
                    Set.of("EXPIRED_PERMISSION"));
            RolePo deleted = insertRole(roles, tenantA.getId(), "DELETED_ROLE", RoleStatus.ACTIVE,
                    Set.of("DELETED_PERMISSION"));
            deleted.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            assertThat(roles.updateById(deleted)).isEqualTo(1);

            insertAssignment(assignmentStore, tenantA.getId(), userA.getId(), active.getId(), null);
            insertAssignment(assignmentStore, tenantA.getId(), userA.getId(), disabled.getId(), null);
            insertAssignment(assignmentStore, tenantA.getId(), userA.getId(), expired.getId(),
                    OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            insertAssignment(assignmentStore, tenantA.getId(), userA.getId(), deleted.getId(), null);

            MyBatisRoleRepository repository = new MyBatisRoleRepository(roles);
            List<com.knowagent.security.domain.role.Role> effective =
                    repository.findEffectiveByUser(TenantId.of(tenantA.getId()), userA.getId());

            assertThat(effective).extracting(com.knowagent.security.domain.role.Role::code)
                    .containsExactly("ACTIVE_ROLE");
            assertThat(effective.getFirst().permissions()).containsExactlyInAnyOrder("USER_READ", "USER_ADMIN");
            assertThat(repository.findEffectiveByUser(TenantId.of(tenantB.getId()), userA.getId())).isEmpty();
            assertThat(repository.findEffectiveByUser(TenantId.of(tenantA.getId()), userB.getId())).isEmpty();
        }
    }

    @Test
    void jsonbInetUuidTimestamptzEnumsAndRefreshOwnershipRoundTrip() throws Exception {
        try (SqlSession session = sessionFactory.openSession(true)) {
            TenantMapper tenants = session.getMapper(TenantMapper.class);
            UserMapper users = session.getMapper(UserMapper.class);
            RoleMapper roles = session.getMapper(RoleMapper.class);
            RefreshTokenMapper tokens = session.getMapper(RefreshTokenMapper.class);
            TenantPo tenant = insertTenant(tenants, "mapping-tenant", TenantStatus.ACTIVE);
            UserPo user = insertUser(users, tenant.getId(), "mapping-user");
            RolePo role = insertRole(roles, tenant.getId(), "MAPPING_ROLE", RoleStatus.ACTIVE,
                    Set.of("MAPPING_READ"));
            RefreshTokenPo token = insertRefreshToken(tokens, tenant.getId(), user.getId());

            assertThat(tenants.selectById(tenant.getId()).getSettings().get("locale").textValue())
                    .isEqualTo("zh-CN");
            assertThat(roles.selectById(role.getId()).getPermissions()).containsExactly("MAPPING_READ");

            MyBatisRefreshTokenStore store = new MyBatisRefreshTokenStore(tokens);
            var loaded = store.findByTokenHash(token.getTokenHash()).orElseThrow();
            assertThat(loaded.id()).isEqualTo(token.getId());
            assertThat(loaded.issuedAt()).isEqualTo(ISSUED_AT.toInstant());
            assertThat(loaded.issuedIp()).isEqualTo(InetAddress.getByName("203.0.113.10"));
            assertThat(loaded.status()).isEqualTo(RefreshTokenStatus.ACTIVE);
            assertThat(loaded.belongsTo(TenantId.of(tenant.getId()), user.getId())).isTrue();
            assertThat(loaded.belongsTo(TenantId.of(UUID.randomUUID()), user.getId())).isFalse();

            assertThat(singleString("SELECT jsonb_typeof(permissions) FROM roles WHERE id = ?", role.getId()))
                    .isEqualTo("array");
            assertThat(singleString("SELECT host(issued_ip) FROM refresh_tokens WHERE id = ?", token.getId()))
                    .isEqualTo("203.0.113.10");

            SQLException invalidStatus = assertThrows(SQLException.class, () -> execute("""
                    INSERT INTO users (id, tenant_id, login_name, display_name, password_hash, status)
                    VALUES (?, ?, ?, ?, ?, 'BROKEN')
                    """, UUID.randomUUID(), tenant.getId(), "invalid-status", "Invalid", "$test-only-hash$"));
            assertThat(invalidStatus.getSQLState()).isEqualTo("23514");
        }
    }

    @Test
    void optimisticLockerRejectsStaleTenantUpdate() {
        UUID tenantId;
        try (SqlSession setup = sessionFactory.openSession(true)) {
            tenantId = insertTenant(setup.getMapper(TenantMapper.class), "version-tenant", TenantStatus.ACTIVE).getId();
        }

        try (SqlSession first = sessionFactory.openSession(false);
             SqlSession second = sessionFactory.openSession(false)) {
            TenantMapper firstMapper = first.getMapper(TenantMapper.class);
            TenantMapper secondMapper = second.getMapper(TenantMapper.class);
            TenantPo firstCopy = firstMapper.selectById(tenantId);
            TenantPo staleCopy = secondMapper.selectById(tenantId);

            firstCopy.setName("First Update");
            assertThat(firstMapper.updateById(firstCopy)).isEqualTo(1);
            first.commit();
            assertThat(firstCopy.getVersion()).isEqualTo(1L);

            staleCopy.setName("Stale Update");
            assertThat(secondMapper.updateById(staleCopy)).isZero();
            second.rollback();
        }
    }

    @Test
    void refreshTokenForUpdateHoldsDatabaseRowLock() throws Exception {
        TenantPo tenant;
        UserPo user;
        RefreshTokenPo token;
        try (SqlSession setup = sessionFactory.openSession(true)) {
            tenant = insertTenant(setup.getMapper(TenantMapper.class), "lock-tenant", TenantStatus.ACTIVE);
            user = insertUser(setup.getMapper(UserMapper.class), tenant.getId(), "lock-user");
            token = insertRefreshToken(setup.getMapper(RefreshTokenMapper.class), tenant.getId(), user.getId());
        }

        try (SqlSession lockingSession = sessionFactory.openSession(false)) {
            MyBatisRefreshTokenStore store = new MyBatisRefreshTokenStore(
                    lockingSession.getMapper(RefreshTokenMapper.class));
            var locked = store.findByTokenHashForUpdate(token.getTokenHash()).orElseThrow();
            assertThat(locked.belongsTo(TenantId.of(tenant.getId()), user.getId())).isTrue();

            try (Connection contender = dataSource.getConnection()) {
                contender.setAutoCommit(false);
                execute(contender, "SET LOCAL lock_timeout = '250ms'");
                SQLException lockTimeout = assertThrows(SQLException.class, () -> execute(contender, """
                        UPDATE refresh_tokens
                        SET version = version + 1
                        WHERE tenant_id = ? AND id = ?
                        """, tenant.getId(), token.getId()));
                assertThat(lockTimeout.getSQLState()).isEqualTo("55P03");
                contender.rollback();
            }
            lockingSession.rollback();
        }
    }

    @Test
    void productionSpringBootContextLoadsPersistenceConfigurationAndRepositoryProxies() throws SQLException {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(KnowAgentApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.url=redis://127.0.0.1:1",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--jwt.issuer=https://knowagent.test",
                        "--jwt.audience=knowagent-api",
                        "--jwt.secret=" + JWT_SECRET,
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR")) {
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getURL())
                        .contains(":" + POSTGRES.getMappedPort(5432) + "/knowagent");
            }
            var tenantRepository = context.getBean(com.knowagent.security.application.port.out.TenantRepository.class);
            assertThat(tenantRepository.findActiveBySlug("spring-context-missing")).isEmpty();
            assertThat(context.getBean(com.knowagent.security.application.port.out.UserRepository.class)).isNotNull();
            assertThat(context.getBean(com.knowagent.security.application.port.out.RoleRepository.class)).isNotNull();
            assertThat(context.getBean(com.knowagent.security.application.port.out.RefreshTokenStore.class)).isNotNull();
            assertThat(context.getBean(com.knowagent.security.application.port.out.UserRoleStore.class)).isNotNull();
        }
    }

    private static TenantPo insertTenant(TenantMapper mapper, String slug, TenantStatus status) {
        TenantPo record = new TenantPo();
        record.setId(UUID.randomUUID());
        record.setSlug(slug);
        record.setName("Tenant " + slug);
        record.setStatus(status);
        record.setSettings(OBJECT_MAPPER.createObjectNode().put("locale", "zh-CN"));
        record.setVersion(0L);
        assertThat(mapper.insert(record)).isEqualTo(1);
        return record;
    }

    private static UserPo insertUser(UserMapper mapper, UUID tenantId, String loginName) {
        UserPo record = new UserPo();
        record.setId(UUID.randomUUID());
        record.setTenantId(tenantId);
        record.setLoginName(loginName);
        record.setDisplayName("User " + loginName);
        record.setPasswordHash("$test-only-hash$");
        record.setStatus(UserStatus.ACTIVE);
        record.setLoginFailedCount(0);
        record.setVersion(0L);
        assertThat(mapper.insert(record)).isEqualTo(1);
        return record;
    }

    private static RolePo insertRole(
            RoleMapper mapper,
            UUID tenantId,
            String code,
            RoleStatus status,
            Set<String> permissions) {
        RolePo record = new RolePo();
        record.setId(UUID.randomUUID());
        record.setTenantId(tenantId);
        record.setCode(code);
        record.setName("Role " + code);
        record.setPermissions(permissions);
        record.setIsSystem(false);
        record.setStatus(status);
        record.setVersion(0L);
        assertThat(mapper.insert(record)).isEqualTo(1);
        return record;
    }

    private static void insertAssignment(
            MyBatisUserRoleStore store,
            UUID tenantId,
            UUID userId,
            UUID roleId,
            OffsetDateTime expiresAt) {
        UserRole assignment = new UserRole(
                UUID.randomUUID(), TenantId.of(tenantId), userId, roleId, null,
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toInstant(),
                expiresAt == null ? null : expiresAt.toInstant());
        store.insert(assignment);
    }

    private static RefreshTokenPo insertRefreshToken(RefreshTokenMapper mapper, UUID tenantId, UUID userId)
            throws Exception {
        RefreshTokenPo record = new RefreshTokenPo();
        record.setId(UUID.randomUUID());
        record.setTenantId(tenantId);
        record.setUserId(userId);
        record.setFamilyId(record.getId());
        record.setTokenHash(record.getId().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        record.setStatus(RefreshTokenStatus.ACTIVE);
        record.setIssuedAt(ISSUED_AT);
        record.setExpiresAt(ISSUED_AT.plusDays(30));
        record.setIssuedIp(InetAddress.getByName("203.0.113.10"));
        record.setUserAgent("persistence-integration-test");
        record.setVersion(0L);
        assertThat(mapper.insert(record)).isEqualTo(1);
        return record;
    }

    private static String singleString(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, sql, parameters);
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
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
