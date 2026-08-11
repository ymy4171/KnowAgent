package com.knowagent.api.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class FlywaySchemaIT {

    private static final Set<String> BUSINESS_TABLES = Set.of(
            "tenants", "departments", "users", "roles", "user_roles",
            "refresh_tokens", "api_keys", "model_providers", "knowledge_bases",
            "knowledge_files", "knowledge_chunks", "agents", "agent_knowledge_bases",
            "conversations", "messages", "message_tool_calls", "message_citations",
            "message_feedback", "agent_runs", "agent_run_requests", "agent_run_events",
            "agent_checkpoints", "tasks", "outbox_events", "inbox_events", "audit_logs",
            "skills", "agent_skills", "agent_tool_grants", "mcp_servers", "agent_mcp_servers"
    );

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowagent")
            .withUsername("knowagent")
            .withPassword("knowagent_test");

    private static Flyway flyway;

    @BeforeAll
    static void migrateSchema() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @Test
    void migratesAllTablesAndSecondMigrateIsEmpty() throws SQLException {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            Set<String> actual = new HashSet<>();
            while (resultSet.next()) {
                actual.add(resultSet.getString(1));
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(BUSINESS_TABLES);
        }
    }

    @Test
    void tenantScopedTablesHaveRequiredTenantColumnAndCompositeKeys() throws SQLException {
        try (Connection connection = connection()) {
            for (String table : BUSINESS_TABLES) {
                if (table.equals("tenants")) {
                    continue;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND column_name = 'tenant_id'
                        """)) {
                    statement.setString(1, table);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        assertThat(resultSet.next()).as("tenant_id column on %s", table).isTrue();
                        assertThat(resultSet.getString(1)).as("tenant_id nullability on %s", table).isEqualTo("NO");
                    }
                }
            }

            assertConstraintExists(connection, "uq_users_tenant_id");
            assertConstraintExists(connection, "uq_knowledge_files_kb_id");
            assertConstraintExists(connection, "uq_messages_tenant_id");
            assertConstraintExists(connection, "uq_agent_runs_tenant_id");
            assertConstraintExists(connection, "uq_agent_run_requests_tenant_id");
        }
    }

    @Test
    void rejectsCrossTenantReferenceAndInvalidStatus() throws SQLException {
        try (Connection connection = connection()) {
            UUID tenantA = createTenant(connection, "tenant-a");
            UUID tenantB = createTenant(connection, "tenant-b");
            UUID departmentA = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO departments (id, tenant_id, code, name)
                    VALUES (?, ?, ?, ?)
                    """, departmentA, tenantA, slug("dept"), "Department A");

            assertSqlState("23503", () -> execute(connection, """
                    INSERT INTO users (
                        id, tenant_id, department_id, login_name, display_name, password_hash
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantB, departmentA, slug("cross-user"), "Cross User", "$argon2id$test"));

            assertSqlState("23514", () -> execute(connection, """
                    INSERT INTO users (
                        id, tenant_id, login_name, display_name, password_hash, status
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantA, slug("bad-status"), "Bad Status", "$argon2id$test", "BROKEN"));
        }
    }

    @Test
    void softDeleteAllowsTenantSlugReuse() throws SQLException {
        try (Connection connection = connection()) {
            String slug = slug("reusable");
            UUID first = createTenant(connection, slug);
            execute(connection, "UPDATE tenants SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", first);
            UUID second = createTenant(connection, slug);

            assertThat(second).isNotEqualTo(first);
        }
    }

    @Test
    void uniqueConstraintsProtectMessageOrderAndInboxIdempotency() throws SQLException {
        try (Connection connection = connection()) {
            ChatFixture fixture = createChatFixture(connection);
            assertSqlState("23505", () -> execute(connection, """
                    INSERT INTO messages (
                        id, tenant_id, conversation_id, sequence_no, role, content
                    ) VALUES (?, ?, ?, 1, 'USER', 'duplicate')
                    """, UUID.randomUUID(), fixture.tenantId(), fixture.conversationId()));

            UUID eventId = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO inbox_events (
                        id, tenant_id, consumer_name, event_id, event_type
                    ) VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), fixture.tenantId(), "agent-worker", eventId, "RUN_REQUESTED");
            assertSqlState("23505", () -> execute(connection, """
                    INSERT INTO inbox_events (
                        id, tenant_id, consumer_name, event_id, event_type
                    ) VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), fixture.tenantId(), "agent-worker", eventId, "RUN_REQUESTED"));
        }
    }

    @Test
    void refreshTokenFamilySupportsRotationAndReplayRevocation() throws SQLException {
        try (Connection connection = connection()) {
            UUID tenantId = createTenant(connection, "token-tenant");
            UUID userId = createUser(connection, tenantId, "token-user");
            UUID rootId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();

            execute(connection, """
                    INSERT INTO refresh_tokens (
                        id, tenant_id, user_id, family_id, token_hash, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, rootId, tenantId, userId, rootId, hex64(), OffsetDateTime.now().plusDays(1));

            assertSqlState("23503", () -> execute(connection, """
                    INSERT INTO refresh_tokens (
                        id, tenant_id, user_id, family_id, parent_token_id, token_hash, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, userId, UUID.randomUUID(), rootId,
                    hex64(), OffsetDateTime.now().plusDays(1)));

            connection.setAutoCommit(false);
            execute(connection, """
                    UPDATE refresh_tokens
                    SET status = 'CONSUMED', consumed_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                    """, tenantId, rootId);
            execute(connection, """
                    INSERT INTO refresh_tokens (
                        id, tenant_id, user_id, family_id, parent_token_id, token_hash, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, childId, tenantId, userId, rootId, rootId, hex64(), OffsetDateTime.now().plusDays(1));
            connection.commit();
            connection.setAutoCommit(true);

            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                    SELECT status
                    FROM refresh_tokens
                    WHERE tenant_id = ? AND id = ?
                    FOR UPDATE
                    """)) {
                lock.setObject(1, tenantId);
                lock.setObject(2, rootId);
                try (ResultSet resultSet = lock.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualTo("CONSUMED");
                }
            }
            execute(connection, """
                    UPDATE refresh_tokens
                    SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                        revoke_reason = 'REPLAY_DETECTED', version = version + 1
                    WHERE tenant_id = ? AND family_id = ? AND status = 'ACTIVE'
                    """, tenantId, rootId);
            connection.commit();
            connection.setAutoCommit(true);

            assertThat(singleString(connection, """
                    SELECT status FROM refresh_tokens WHERE tenant_id = ? AND id = ?
                    """, tenantId, childId)).isEqualTo("REVOKED");
        }
    }

    @Test
    void partialIndexAllowsQueuedRunsButRejectsSecondActiveRun() throws SQLException {
        try (Connection connection = connection()) {
            ChatFixture fixture = createChatFixture(connection);
            UUID firstRun = createRun(connection, fixture, "RUNNING");
            UUID secondRun = createRun(connection, fixture, "PENDING");

            assertThat(firstRun).isNotEqualTo(secondRun);
            assertSqlState("23505", () -> execute(connection, """
                    UPDATE agent_runs
                    SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND status = 'PENDING'
                    """, fixture.tenantId(), secondRun));
        }
    }

    @Test
    void skipLockedPreventsTwoOutboxPublishersClaimingSameEvent() throws SQLException {
        UUID tenantId;
        UUID eventId = UUID.randomUUID();
        try (Connection setup = connection()) {
            tenantId = createTenant(setup, "outbox-tenant");
            execute(setup, """
                    INSERT INTO outbox_events (
                        id, tenant_id, aggregate_type, aggregate_id, event_type, payload
                    ) VALUES (?, ?, 'TEST', ?, 'TEST_EVENT', '{}'::jsonb)
                    """, eventId, tenantId, UUID.randomUUID().toString());
        }

        try (Connection first = connection(); Connection second = connection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            assertThat(claimOutboxEvent(first, tenantId)).contains(eventId);
            assertThat(claimOutboxEvent(second, tenantId)).isEmpty();

            first.rollback();
            second.rollback();
        }
    }

    @Test
    void messageRequestRunOutboxAndSequenceRollbackTogether() throws SQLException {
        ChatFixture fixture;
        try (Connection setup = connection()) {
            fixture = createChatFixture(setup);
        }

        UUID messageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();

        try (Connection transaction = connection()) {
            transaction.setAutoCommit(false);
            long sequence;
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE conversations
                    SET next_message_sequence = next_message_sequence + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                    RETURNING next_message_sequence - 1
                    """)) {
                statement.setObject(1, fixture.tenantId());
                statement.setObject(2, fixture.conversationId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    sequence = resultSet.getLong(1);
                }
            }

            execute(transaction, """
                    INSERT INTO messages (
                        id, tenant_id, conversation_id, sequence_no, role, content, run_id, request_id
                    ) VALUES (?, ?, ?, ?, 'USER', 'transactional question', ?, ?)
                    """, messageId, fixture.tenantId(), fixture.conversationId(), sequence, runId, requestId);
            execute(transaction, """
                    INSERT INTO agent_runs (
                        id, tenant_id, conversation_id, agent_id, user_id, input_message_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, runId, fixture.tenantId(), fixture.conversationId(), fixture.agentId(),
                    fixture.userId(), messageId);
            execute(transaction, """
                    INSERT INTO agent_run_requests (
                        id, tenant_id, conversation_id, agent_id, user_id, run_id, input_message_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, requestId, fixture.tenantId(), fixture.conversationId(), fixture.agentId(),
                    fixture.userId(), runId, messageId);
            execute(transaction, """
                    INSERT INTO outbox_events (
                        id, tenant_id, aggregate_type, aggregate_id, event_type, payload
                    ) VALUES (?, ?, 'AGENT_REQUEST', ?, 'AGENT_REQUESTED', '{}'::jsonb)
                    """, outboxId, fixture.tenantId(), requestId.toString());
            transaction.rollback();
        }

        try (Connection verification = connection()) {
            assertThat(countById(verification, "messages", messageId)).isZero();
            assertThat(countById(verification, "agent_runs", runId)).isZero();
            assertThat(countById(verification, "agent_run_requests", requestId)).isZero();
            assertThat(countById(verification, "outbox_events", outboxId)).isZero();
            assertThat(singleLong(verification, """
                    SELECT next_message_sequence
                    FROM conversations
                    WHERE tenant_id = ? AND id = ?
                    """, fixture.tenantId(), fixture.conversationId())).isEqualTo(2L);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID createTenant(Connection connection, String prefix) throws SQLException {
        UUID id = UUID.randomUUID();
        String tenantSlug = prefix.contains("-") && prefix.length() > 15 ? prefix : slug(prefix);
        execute(connection, "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                id, tenantSlug, "Tenant " + tenantSlug);
        return id;
    }

    private static UUID createUser(Connection connection, UUID tenantId, String prefix) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO users (id, tenant_id, login_name, display_name, password_hash)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, slug(prefix), "User " + prefix, "$argon2id$test");
        return id;
    }

    private static ChatFixture createChatFixture(Connection connection) throws SQLException {
        UUID tenantId = createTenant(connection, "chat-tenant");
        UUID userId = createUser(connection, tenantId, "chat-user");
        UUID providerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        execute(connection, """
                INSERT INTO model_providers (
                    id, tenant_id, provider_key, display_name, base_url
                ) VALUES (?, ?, ?, 'Test Provider', 'http://model.test')
                """, providerId, tenantId, slug("provider"));
        execute(connection, """
                INSERT INTO agents (
                    id, tenant_id, slug, name, system_prompt, status,
                    model_provider_id, chat_model
                ) VALUES (?, ?, ?, 'Test Agent', 'Answer precisely.', 'ACTIVE', ?, 'test-model')
                """, agentId, tenantId, slug("agent"), providerId);
        execute(connection, """
                INSERT INTO conversations (
                    id, tenant_id, user_id, agent_id, title, next_message_sequence
                ) VALUES (?, ?, ?, ?, 'Test Conversation', 2)
                """, conversationId, tenantId, userId, agentId);
        execute(connection, """
                INSERT INTO messages (
                    id, tenant_id, conversation_id, sequence_no, role, content
                ) VALUES (?, ?, ?, 1, 'USER', 'initial question')
                """, messageId, tenantId, conversationId);

        return new ChatFixture(tenantId, userId, agentId, conversationId, messageId);
    }

    private static UUID createRun(Connection connection, ChatFixture fixture, String status) throws SQLException {
        UUID runId = UUID.randomUUID();
        execute(connection, """
                INSERT INTO agent_runs (
                    id, tenant_id, conversation_id, agent_id, user_id,
                    input_message_id, status, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'RUNNING' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, runId, fixture.tenantId(), fixture.conversationId(), fixture.agentId(),
                fixture.userId(), fixture.messageId(), status, status);
        return runId;
    }

    private static Set<UUID> claimOutboxEvent(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id
                FROM outbox_events
                WHERE tenant_id = ?
                  AND status = 'PENDING'
                  AND next_retry_at <= CURRENT_TIMESTAMP
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """)) {
            statement.setObject(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<UUID> ids = new HashSet<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getObject(1, UUID.class));
                }
                return ids;
            }
        }
    }

    private static void assertConstraintExists(Connection connection, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM pg_constraint
                WHERE conname = ?
                """)) {
            statement.setString(1, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).as(constraintName).isEqualTo(1L);
            }
        }
    }

    private static long countById(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long singleLong(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static String singleString(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }

    private static void assertSqlState(String expected, SqlOperation operation) {
        SQLException exception = assertThrows(SQLException.class, operation::run);
        assertThat(exception.getSQLState()).isEqualTo(expected);
    }

    private static String slug(String prefix) {
        return prefix.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String hex64() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }

    private record ChatFixture(
            UUID tenantId,
            UUID userId,
            UUID agentId,
            UUID conversationId,
            UUID messageId
    ) {
    }
}
