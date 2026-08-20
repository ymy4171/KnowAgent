package com.knowagent.api.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.api.KnowAgentApiApplication;
import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.InboxEventStore;
import com.knowagent.observability.application.port.out.OutboxEventStore;
import com.knowagent.observability.application.port.out.TaskStore;
import com.knowagent.observability.application.port.out.TaskTransition;
import com.knowagent.observability.application.service.OutboxPublisherService;
import com.knowagent.observability.application.service.SubmitTaskCommand;
import com.knowagent.observability.application.service.TaskSubmissionResult;
import com.knowagent.observability.application.service.TaskSubmissionService;
import com.knowagent.observability.inbox.InboxEvent;
import com.knowagent.observability.outbox.OutboxEvent;
import com.knowagent.observability.outbox.OutboxStatus;
import com.knowagent.observability.outbox.RetryPolicy;
import com.knowagent.observability.task.Task;
import com.knowagent.observability.task.TaskStatus;
import com.knowagent.security.application.port.out.PasswordHasher;
import com.knowagent.security.application.service.AdminBootstrap;
import com.knowagent.security.application.service.AdminBootstrapRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the task + transactional outbox + inbox foundation on a
 * real PostgreSQL 16 (Testcontainers): the same-transaction write boundary, competing
 * publisher claims, lease semantics, retry/backoff/dead-letter, optimistic-lock
 * guards, inbox idempotency, cross-tenant isolation, and the {@code GET
 * /api/v1/tasks/{id}} endpoint.
 *
 * <p>Every test seeds only the rows it needs and deletes them in {@link #cleanup()},
 * so the cross-tenant outbox claim ({@code FOR UPDATE SKIP LOCKED}) deterministically
 * returns exactly the rows each test just created.
 */
@Testcontainers
class TaskOutboxInboxIT {

    private static final String ISSUER = "https://knowagent.test";
    private static final String AUDIENCE = "knowagent-api";
    private static final String JWT_SECRET = Base64.getEncoder().encodeToString(
            "integration-test-only-key-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    private static final String RAW_PASSWORD = "CorrectHorseBatteryStaple1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("knowagent")
                    .withUsername("knowagent")
                    .withPassword("integration_only");

    private static ConfigurableApplicationContext context;
    private static MockMvc mockMvc;
    private static DataSource dataSource;
    /** The application's DataSource, the one the transaction manager uses. */
    private static DataSource appDataSource;
    private static PasswordHasher passwordHasher;
    private static TransactionTemplate transactionTemplate;

    private static TaskSubmissionService submission;
    private static TaskStore taskStore;
    private static OutboxEventStore outboxStore;
    private static InboxEventStore inboxStore;
    private static OutboxPublisherService publisher;

    // Seeded identities (created in @BeforeAll), shared across tests.
    private static UUID alphaTenant;
    private static UUID betaTenant;
    private static String adminToken;

    // Rows created by the current test, removed in @AfterEach so the global claim
    // never sees leftovers from an earlier test.
    private final List<UUID> tasksToDelete = new ArrayList<>();
    private final List<UUID> eventsToDelete = new ArrayList<>();
    private final List<UUID> inboxToDelete = new ArrayList<>();
    private final List<UUID> knowledgeBasesToDelete = new ArrayList<>();

    @BeforeAll
    static void bootContext() throws Exception {
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
                        "--bootstrap.enabled=false",
                        "--spring.datasource.hikari.maximum-pool-size=24",
                        "--jwt.issuer=" + ISSUER,
                        "--jwt.audience=" + AUDIENCE,
                        "--jwt.secret=" + JWT_SECRET,
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration=ERROR");
        mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        appDataSource = context.getBean(DataSource.class);
        passwordHasher = context.getBean(PasswordHasher.class);
        submission = context.getBean(TaskSubmissionService.class);
        taskStore = context.getBean(TaskStore.class);
        outboxStore = context.getBean(OutboxEventStore.class);
        inboxStore = context.getBean(InboxEventStore.class);
        publisher = context.getBean(OutboxPublisherService.class);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        AdminBootstrap bootstrap = context.getBean(AdminBootstrap.class);
        bootstrap.initialize(new AdminBootstrapRequest("alpha", null, "admin@alpha.test", null, RAW_PASSWORD));
        bootstrap.initialize(new AdminBootstrapRequest("beta", null, "admin@beta.test", null, RAW_PASSWORD));
        seedViewer();

        alphaTenant = tenantId("alpha");
        betaTenant = tenantId("beta");
        adminToken = login("alpha", "admin@alpha.test");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @AfterEach
    void cleanup() {
        for (UUID id : inboxToDelete) {
            deleteById("inbox_events", id);
        }
        for (UUID id : eventsToDelete) {
            deleteById("outbox_events", id);
        }
        for (UUID id : tasksToDelete) {
            deleteById("tasks", id);
        }
        for (UUID id : knowledgeBasesToDelete) {
            deleteById("knowledge_bases", id);
        }
    }

    // ------------------------------------------------------------- transaction

    @Test
    void businessRecordTaskAndOutboxEventRollBackTogether() {
        UUID knowledgeBaseId = UUID.randomUUID();
        knowledgeBasesToDelete.add(knowledgeBaseId);
        TaskSubmissionResult[] submitted = new TaskSubmissionResult[1];

        try {
            transactionTemplate.executeWithoutResult(status -> {
                insertKnowledgeBase(alphaTenant, knowledgeBaseId);
                submitted[0] = submission.submit(validCommand(alphaTenant));
                throw new IllegalStateException("roll back the whole transaction");
            });
            throw new AssertionError("the transaction template must have propagated the failure");
        } catch (IllegalStateException expected) {
            // The business record, the task and the outbox event were all written
            // in one transaction and all rolled back.
        }

        assertThat(findKnowledgeBase(knowledgeBaseId)).isNull();
        assertThat(taskStore.findById(TenantId.of(alphaTenant), submitted[0].taskId())).isEmpty();
        assertThat(outboxStore.findById(TenantId.of(alphaTenant), submitted[0].outboxEventId())).isEmpty();
    }

    @Test
    void submittingAwayFromATransactionPersistsTaskAndEventIndependently() {
        TaskSubmissionResult result = submission.submit(validCommand(alphaTenant));
        tasksToDelete.add(result.taskId());
        eventsToDelete.add(result.outboxEventId());

        assertThat(taskStore.findById(TenantId.of(alphaTenant), result.taskId()))
                .isPresent()
                .get()
                .extracting(task -> task.status())
                .isEqualTo(TaskStatus.PENDING);
        assertThat(outboxStore.findById(TenantId.of(alphaTenant), result.outboxEventId()))
                .isPresent()
                .get()
                .extracting(event -> event.status())
                .isEqualTo(OutboxStatus.PENDING);
    }

    // ------------------------------------------------------- competing claims

    @Test
    void twoConcurrentPublishersNeverClaimTheSameEvent() throws Exception {
        // Six events across both tenants: the claim spans tenants by design, but
        // every claimed row must appear in exactly one publisher's hand.
        List<OutboxEvent> seeded = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            seeded.add(seedEvent(alphaTenant, 3));
            seeded.add(seedEvent(betaTenant, 3));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<UUID>> first = pool.submit(() -> {
                start.await();
                return claimIds("worker-a", 10);
            });
            Future<List<UUID>> second = pool.submit(() -> {
                start.await();
                return claimIds("worker-b", 10);
            });
            start.countDown();

            List<UUID> claimedByA = first.get(30, TimeUnit.SECONDS);
            List<UUID> claimedByB = second.get(30, TimeUnit.SECONDS);

            // Disjoint: no event was handed to both publishers.
            Set<UUID> overlap = new HashSet<>(claimedByA);
            overlap.retainAll(claimedByB);
            assertThat(overlap).isEmpty();

            // Complete: every seeded event was claimed exactly once.
            Set<UUID> expected = new HashSet<>();
            seeded.forEach(event -> expected.add(event.id()));
            Set<UUID> claimed = new HashSet<>(claimedByA);
            claimed.addAll(claimedByB);
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUnexpiredLeaseIsNotPreemptableButAnExpiredOneIsReclaimed() {
        seedEvent(alphaTenant, 3);

        List<OutboxEvent> first = outboxStore.claimReady(1, Instant.now(), "worker-a", Duration.ofSeconds(30));
        assertThat(first).hasSize(1);
        UUID eventId = first.get(0).id();
        eventsToDelete.add(eventId);

        // Within the lease the event stays PROCESSING and cannot be claimed again.
        List<OutboxEvent> second = outboxStore.claimReady(1, Instant.now().plusSeconds(10), "worker-b",
                Duration.ofSeconds(30));
        assertThat(second).isEmpty();

        // Force the lease to expire, then the event becomes reclaimable.
        expireLease(eventId);
        List<OutboxEvent> third = outboxStore.claimReady(1, Instant.now(), "worker-c", Duration.ofSeconds(30));
        assertThat(third).hasSize(1);
        assertThat(third.get(0).id()).isEqualTo(eventId);
        assertThat(third.get(0).lockedBy()).isEqualTo("worker-c");
    }

    // ----------------------------------------------------- retries / backoff / DLQ

    @Test
    void failuresAdvanceRetriesThenDeadLetterWithBackoff() {
        seedEvent(alphaTenant, 3);
        OutboxEvent firstClaim = outboxStore.claimReady(1, Instant.now(), "worker-a", Duration.ofSeconds(30))
                .get(0);
        eventsToDelete.add(firstClaim.id());

        publisher.fail(firstClaim, "boom one");
        OutboxEvent afterOne = requireEvent(firstClaim.id());
        assertThat(afterOne.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterOne.retryCount()).isEqualTo(1);
        assertThat(afterOne.nextRetryAt()).isAfter(Instant.now()); // backoff pushes it to the future
        assertThat(afterOne.lastError()).isEqualTo("boom one");

        OutboxEvent secondClaim = outboxStore.claimReady(1, afterOne.nextRetryAt().plusMillis(1),
                "worker-a", Duration.ofSeconds(30)).get(0);
        publisher.fail(secondClaim, "boom two");
        OutboxEvent afterTwo = requireEvent(firstClaim.id());
        assertThat(afterTwo.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterTwo.retryCount()).isEqualTo(2);

        OutboxEvent thirdClaim = outboxStore.claimReady(1, afterTwo.nextRetryAt().plusMillis(1),
                "worker-a", Duration.ofSeconds(30)).get(0);
        publisher.fail(thirdClaim, "boom three");
        OutboxEvent afterThree = requireEvent(firstClaim.id());
        assertThat(afterThree.status()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(afterThree.retryCount()).isEqualTo(3);
        assertThat(afterThree.publishedAt()).isNull();

        // A dead letter is no longer claimable.
        assertThat(outboxStore.claimReady(1, Instant.now(), "worker-a", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void statusAndVersionGuardsPreventCompletingAStaleEvent() {
        seedEvent(alphaTenant, 3);
        OutboxEvent claimed = outboxStore.claimReady(1, Instant.now(), "worker-a", Duration.ofSeconds(30))
                .get(0);
        eventsToDelete.add(claimed.id());

        // Publisher A publishes and wins the race.
        assertThat(outboxStore.markPublished(claimed)).isEqualTo(1);
        // The same stale view can no longer publish...
        assertThat(outboxStore.markPublished(claimed)).isZero();
        // ...and a second publisher with the same stale view cannot fail it either.
        OutboxEvent target = claimed.failure("late", Instant.now(), RetryPolicy.DEFAULT);
        assertThat(outboxStore.markFailed(claimed, target)).isZero();

        OutboxEvent published = requireEvent(claimed.id());
        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
    }

    @Test
    void exhaustedTaskCannotBeReclaimedOrRetriedButResolvesToFailed() {
        // A PENDING task that already burned its attempt budget (3/3) must never be
        // claimed again: the claim SQL guards attempt_count < max_attempts.
        UUID pendingId = UUID.randomUUID();
        taskStore.save(new Task(pendingId, TenantId.of(alphaTenant), "ingest", "knowledge_base", "kb-1", null,
                TaskStatus.PENDING, null, 0, OBJECT_MAPPER.createObjectNode(), null, 3, 3, null, null, null,
                null, null, null, false, null, null, 0, Instant.EPOCH, Instant.EPOCH));
        tasksToDelete.add(pendingId);

        assertThat(taskStore.claim(TenantId.of(alphaTenant), pendingId, "worker-a",
                Instant.now(), Duration.ofSeconds(30))).isEmpty();

        // A RUNNING task whose budget is exhausted cannot be scheduled for another
        // retry (PENDING) - the store refuses - but resolves cleanly to the terminal
        // FAILED instead of tripping the ck_tasks_attempts CHECK on claim.
        UUID runningId = UUID.randomUUID();
        Task running = new Task(runningId, TenantId.of(alphaTenant), "ingest", "knowledge_base", "kb-1", null,
                TaskStatus.RUNNING, null, 0, OBJECT_MAPPER.createObjectNode(), null, 3, 3, null,
                "worker-a", Instant.now().plusSeconds(30), null, null, null, false, Instant.now(), null,
                0, Instant.EPOCH, Instant.EPOCH);
        taskStore.save(running);
        tasksToDelete.add(runningId);

        TaskTransition retry = new TaskTransition(TaskStatus.PENDING, null, 0,
                OBJECT_MAPPER.createObjectNode(), null, "boom", true, Instant.now().plusSeconds(5));
        assertThat(taskStore.transition(running, retry)).isZero();

        TaskTransition failed = new TaskTransition(TaskStatus.FAILED, "ingest", 100,
                OBJECT_MAPPER.createObjectNode(), "ERR", "boom", false, null);
        assertThat(taskStore.transition(running, failed)).isEqualTo(1);

        // The final failure landed on FAILED - it never re-entered PENDING.
        assertThat(taskStore.findById(TenantId.of(alphaTenant), runningId))
                .get()
                .extracting(task -> task.status())
                .isEqualTo(TaskStatus.FAILED);
    }

    // ------------------------------------------------------------ inbox dedup

    @Test
    void duplicateInboxEventIsProcessedOnceAndReportedAsAlreadyProcessed() {
        UUID eventId = UUID.randomUUID();
        InboxEvent first = new InboxEvent(UUID.randomUUID(), TenantId.of(alphaTenant), "kb-worker",
                eventId, "kb.ready", null, Instant.now());
        inboxToDelete.add(first.id());

        // First receipt creates the row.
        assertThat(inboxStore.recordProcessed(first)).isTrue();
        // A replay of the same event for the same consumer is a no-op, not an error.
        assertThat(inboxStore.recordProcessed(first)).isFalse();

        assertThat(inboxStore.wasProcessed(TenantId.of(alphaTenant), "kb-worker", eventId)).isTrue();
        // Different consumer or different event -> not processed.
        assertThat(inboxStore.wasProcessed(TenantId.of(alphaTenant), "other-worker", eventId)).isFalse();
        assertThat(inboxStore.wasProcessed(TenantId.of(alphaTenant), "kb-worker", UUID.randomUUID())).isFalse();
    }

    // ------------------------------------------------------- cross-tenant walls

    @Test
    void tenantAStoresAreInvisibleToTenantB() {
        TaskSubmissionResult alphaResult = submission.submit(validCommand(alphaTenant));
        TaskSubmissionResult betaResult = submission.submit(validCommand(betaTenant));
        tasksToDelete.add(alphaResult.taskId());
        tasksToDelete.add(betaResult.taskId());
        eventsToDelete.add(alphaResult.outboxEventId());
        eventsToDelete.add(betaResult.outboxEventId());

        assertThat(taskStore.findById(TenantId.of(betaTenant), alphaResult.taskId())).isEmpty();
        assertThat(taskStore.findById(TenantId.of(alphaTenant), betaResult.taskId())).isEmpty();
        // Beta cannot claim alpha's task.
        assertThat(taskStore.claim(TenantId.of(betaTenant), alphaResult.taskId(), "worker-a",
                Instant.now(), Duration.ofSeconds(30))).isEmpty();
        assertThat(outboxStore.findById(TenantId.of(betaTenant), alphaResult.outboxEventId())).isEmpty();
        assertThat(outboxStore.findById(TenantId.of(alphaTenant), betaResult.outboxEventId())).isEmpty();
        // The tenant-scoped task read is unaffected by the id being "known".
        assertThat(taskStore.findById(TenantId.of(betaTenant), alphaResult.taskId())).isEmpty();
    }

    // -------------------------------------------------------------- HTTP / API

    @Test
    void anonymousTaskReadIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
                });
    }

    @Test
    void userWithoutTaskReadPermissionGets403() throws Exception {
        String token = login("alpha", "viewer@alpha.test");

        mockMvc.perform(get("/api/v1/tasks/" + UUID.randomUUID())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    void adminReadsItsOwnTaskWithoutPayloadOrResultLeakage() throws Exception {
        TaskSubmissionResult result = submission.submit(validCommand(alphaTenant));
        tasksToDelete.add(result.taskId());
        eventsToDelete.add(result.outboxEventId());

        MvcResult mvc = mockMvc.perform(get("/api/v1/tasks/" + result.taskId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(mvc.getResponse().getContentAsString());

        assertThat(body.path("id").asText()).isEqualTo(result.taskId().toString());
        assertThat(body.path("taskType").asText()).isEqualTo("ingest");
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("progress").asInt()).isZero();
        assertThat(body.path("attemptCount").asInt()).isZero();
        // Payload/result can carry storage keys or parsed content; the response
        // deliberately never exposes them, nor internal tenant/lock fields.
        assertThat(body.has("payload")).isFalse();
        assertThat(body.has("result")).isFalse();
        assertThat(body.has("tenantId")).isFalse();
        assertThat(body.has("lockedBy")).isFalse();
        assertThat(body.has("lockedUntil")).isFalse();
        assertThat(body.has("version")).isFalse();
    }

    @Test
    void crossTenantTaskReadIsAUniform404() throws Exception {
        TaskSubmissionResult betaResult = submission.submit(validCommand(betaTenant));
        tasksToDelete.add(betaResult.taskId());
        eventsToDelete.add(betaResult.outboxEventId());

        mockMvc.perform(get("/api/v1/tasks/" + betaResult.taskId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(result -> {
                    JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
                    assertThat(body.path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
                });

        // A task id that exists nowhere looks identical to an outsider.
        mockMvc.perform(get("/api/v1/tasks/" + UUID.randomUUID())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------- helpers

    private List<UUID> claimIds(String workerId, int limit) {
        List<UUID> ids = new ArrayList<>();
        for (OutboxEvent event : outboxStore.claimReady(limit, Instant.now(), workerId, Duration.ofSeconds(30))) {
            ids.add(event.id());
        }
        return ids;
    }

    private OutboxEvent seedEvent(UUID tenantId, int maxRetries) {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), TenantId.of(tenantId), "knowledge_base", "kb-1", "kb.created",
                OBJECT_MAPPER.createObjectNode(), OBJECT_MAPPER.createObjectNode(),
                OutboxStatus.PENDING, 0, maxRetries, Instant.EPOCH, null, null, null, null, 0, Instant.EPOCH);
        outboxStore.append(event);
        eventsToDelete.add(event.id());
        return event;
    }

    private OutboxEvent requireEvent(UUID eventId) {
        return outboxStore.findById(TenantId.of(alphaTenant), eventId).orElseThrow();
    }

    private static SubmitTaskCommand validCommand(UUID tenantId) {
        return new SubmitTaskCommand(
                TenantId.of(tenantId), "ingest", "knowledge_base", "kb-1", null,
                OBJECT_MAPPER.createObjectNode(), 3, "kb.created",
                OBJECT_MAPPER.createObjectNode(), OBJECT_MAPPER.createObjectNode(), 3);
    }

    private void insertKnowledgeBase(UUID tenantId, UUID id) {
        // The app DataSource is the one the transaction manager binds to, so a
        // connection fetched through DataSourceUtils joins the running transaction
        // and rolls back together with the task and outbox writes.
        Connection connection = DataSourceUtils.getConnection(appDataSource);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_bases (id, tenant_id, slug, name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, "kb-" + id);
            statement.setString(4, "KB " + id);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, appDataSource);
        }
    }

    private UUID findKnowledgeBase(UUID id) {
        return singleUuidOrNull("SELECT id FROM knowledge_bases WHERE id = ?", id);
    }

    private void expireLease(UUID eventId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE outbox_events
                     SET locked_until = now() - interval '5 seconds'
                     WHERE id = ?
                     """)) {
            statement.setObject(1, eventId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteById(String table, UUID id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void seedViewer() {
        // Seeded before alphaTenant is resolved, so resolve the tenant first.
        UUID tenant = tenantId("alpha");
        String passwordHash = passwordHasher.encode(RAW_PASSWORD);

        UUID viewerRole = insertRole(tenant, "VIEWER", "[]");
        UUID viewerId = insertUser(tenant, "viewer@alpha.test", "Viewer", "ACTIVE", passwordHash);
        insertAssignment(tenant, viewerId, viewerRole);
    }

    private static String login(String tenantSlug, String loginName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "tenantSlug", tenantSlug,
                                "loginName", loginName,
                                "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        return body.path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static UUID tenantId(String slug) {
        return singleUuid("SELECT id FROM tenants WHERE slug = ?", slug);
    }

    private static UUID insertUser(UUID tenantId, String loginName, String displayName,
                                   String status, String passwordHash) {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (id, tenant_id, login_name, display_name, password_hash, status, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, loginName);
            statement.setString(4, displayName);
            statement.setString(5, passwordHash);
            statement.setString(6, status);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID insertRole(UUID tenantId, String code, String permissionsJson) {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO roles (id, tenant_id, code, name, permissions, is_system, status)
                     VALUES (?, ?, ?, ?, ?::jsonb, false, 'ACTIVE')
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, code);
            statement.setString(4, code);
            statement.setString(5, permissionsJson);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void insertAssignment(UUID tenantId, UUID userId, UUID roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO user_roles (tenant_id, user_id, role_id, granted_at, expires_at)
                     VALUES (?, ?, ?, ?, NULL)
                     """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, roleId);
            statement.setObject(4, OffsetDateTime.now().minusDays(2));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID singleUuid(String sql, Object... parameters) {
        return singleUuidOrNull(sql, parameters);
    }

    private static UUID singleUuidOrNull(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getObject(1, UUID.class) : null;
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
