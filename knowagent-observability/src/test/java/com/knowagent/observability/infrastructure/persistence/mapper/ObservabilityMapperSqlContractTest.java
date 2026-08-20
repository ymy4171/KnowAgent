package com.knowagent.observability.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.knowagent.observability.infrastructure.persistence.entity.InboxEventPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the observability module's tenant-isolation SQL contract.
 *
 * <p>Every custom statement bypasses the tenant-line plugin
 * ({@code @InterceptorIgnore(tenantLine = "1")}) because the same mappers serve
 * authenticated requests and worker execution, and workers have no
 * {@code TenantContext}. The explicit {@code tenant_id} condition is therefore the
 * single isolation mechanism, so each bypassed statement must carry one - with one
 * documented exception: the outbox claim ({@code OutboxEventMapper.selectClaimable})
 * spans <em>all</em> tenants by design (competing publishers), and every claimed row
 * carries its {@code tenant_id} for the downstream worker operations.
 */
class ObservabilityMapperSqlContractTest {

    /** The exact set of tenant-line bypasses. Adding one fails this test on purpose. */
    private static final Set<String> EXPECTED_BYPASS = new TreeSet<>(Set.of(
            "InboxEventMapper.recordProcessed",
            "InboxEventMapper.selectProcessed",
            "OutboxEventMapper.markFailed",
            "OutboxEventMapper.markProcessing",
            "OutboxEventMapper.markPublished",
            "OutboxEventMapper.selectByIdAndTenant",
            "OutboxEventMapper.selectClaimable",
            "TaskMapper.claimForExecution",
            "TaskMapper.selectByIdAndTenant",
            "TaskMapper.selectByIdAndTenantForUpdate",
            "TaskMapper.updateRunningProgress",
            "TaskMapper.transitionTask"));

    /** The only bypass that legitimately has no tenant_id condition. */
    private static final String DOCUMENTED_GLOBAL_EXCEPTION = "OutboxEventMapper.selectClaimable";

    @Test
    void everyCustomStatementBypassesTenantLineWithExplicitTenantOrDocumentedException() throws Exception {
        for (Class<?> mapper : List.of(TaskMapper.class, OutboxEventMapper.class, InboxEventMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = rawSql(method);
                if (sql == null) {
                    continue; // BaseMapper inherited methods are not annotated.
                }
                InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
                assertThat(ignore)
                        .as(mapper.getSimpleName() + "." + method.getName() + " must bypass the tenant line")
                        .isNotNull();
                assertThat(ignore.tenantLine()).isEqualTo("1");
            }
        }
    }

    @Test
    void bypassedStatementsCarryExplicitTenantIdExceptTheDocumentedClaim() {
        for (Class<?> mapper : List.of(TaskMapper.class, OutboxEventMapper.class, InboxEventMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = rawSql(method);
                if (sql == null || !isBypassed(method)) {
                    continue;
                }
                String name = mapper.getSimpleName() + "." + method.getName();
                if (DOCUMENTED_GLOBAL_EXCEPTION.equals(name)) {
                    continue;
                }
                if (method.getAnnotation(Insert.class) != null) {
                    // An INSERT scopes by writing the tenant_id column, not an equality.
                    assertThat(sql)
                            .as(name + " must explicitly insert the tenant_id")
                            .contains("tenant_id", "#{tenantid}");
                } else {
                    assertThat(sql)
                            .as(name + " must be explicitly tenant-scoped")
                            .contains("tenant_id = #{tenantid}");
                }
            }
        }
    }

    @Test
    void onlyTheDocumentedBypassSetIsAllowed() {
        TreeSet<String> bypassed = new TreeSet<>();
        for (Class<?> mapper : List.of(TaskMapper.class, OutboxEventMapper.class, InboxEventMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (isBypassed(method)) {
                    bypassed.add(mapper.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(bypassed).containsExactlyElementsOf(EXPECTED_BYPASS);
    }

    @Test
    void outboxClaimUsesSkipLockedOrderedByNextRetryThenCreatedAt() throws Exception {
        Method method = OutboxEventMapper.class.getMethod("selectClaimable", int.class, java.time.OffsetDateTime.class);
        String sql = sql(method);

        assertThat(sql).contains(
                "from outbox_events",
                "next_retry_at <= #{now}",
                "status = 'pending'",
                "status = 'processing'",
                "locked_until < #{now}",
                "order by next_retry_at, created_at",
                "limit #{limit}",
                "for update skip locked");
    }

    @Test
    void claimProcessingIsTenantScopedAndLeaseGuarded() throws Exception {
        Method method = OutboxEventMapper.class.getMethod(
                "markProcessing", java.util.UUID.class, java.util.UUID.class, long.class,
                String.class, java.time.OffsetDateTime.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update outbox_events",
                "status = 'processing'",
                "locked_by = #{workerid}",
                "locked_until = #{lockeduntil}",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "id = #{id}",
                "status in ('pending', 'processing')",
                "version = #{version}");
    }

    @Test
    void publishAndFailAreTenantScopedAndStatusVersionGuarded() throws Exception {
        Method publish = OutboxEventMapper.class.getMethod(
                "markPublished", java.util.UUID.class, java.util.UUID.class, long.class);
        String publishSql = updateSql(publish);
        assertThat(publishSql).contains(
                "update outbox_events",
                "status = 'published'",
                "published_at = current_timestamp",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "status = 'processing'",
                "version = #{version}");

        Method fail = OutboxEventMapper.class.getMethod(
                "markFailed", java.util.UUID.class, java.util.UUID.class, long.class,
                com.knowagent.observability.outbox.OutboxStatus.class, int.class,
                java.time.OffsetDateTime.class, String.class);
        String failSql = updateSql(fail);
        assertThat(failSql).contains(
                "update outbox_events",
                "retry_count = #{retrycount}",
                "next_retry_at = #{nextretryat}",
                "last_error = #{lasterror}",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "status = 'processing'",
                "version = #{version}");
    }

    @Test
    void taskClaimIsTenantScopedWithStatusAndLeaseGuard() throws Exception {
        Method method = TaskMapper.class.getMethod(
                "claimForExecution", java.util.UUID.class, java.util.UUID.class, long.class,
                String.class, java.time.OffsetDateTime.class, java.time.OffsetDateTime.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update tasks",
                "status = 'running'",
                "stage = null",
                "progress = 0",
                "result = null",
                "next_retry_at = null",
                "locked_by = #{workerid}",
                "locked_until = #{lockeduntil}",
                "error_code = null",
                "error_message = null",
                "retryable = false",
                "completed_at = null",
                "attempt_count = attempt_count + 1",
                "attempt_count < max_attempts",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "id = #{id}",
                "version = #{version}",
                "status = 'pending'",
                "status = 'running'");
        assertThat(updateSql).doesNotContain("status = 'pending' or status = 'pending'");
    }

    @Test
    void taskTransitionIsTenantScopedWithStatusAndVersionGuard() throws Exception {
        Method method = TaskMapper.class.getMethod("transitionTask",
                java.util.UUID.class, java.util.UUID.class, long.class,
                com.knowagent.observability.task.TaskStatus.class,
                com.knowagent.observability.task.TaskStatus.class,
                String.class, int.class, com.fasterxml.jackson.databind.JsonNode.class,
                String.class, String.class, boolean.class, java.time.OffsetDateTime.class);
        String updateSql = updateSql(method);

        assertThat(updateSql).contains(
                "update tasks",
                "status = #{targetstatus}",
                "locked_by = null",
                "locked_until = null",
                "version = version + 1",
                "where tenant_id = #{tenantid}",
                "id = #{id}",
                "version = #{version}",
                "status = #{expectedstatus}");
    }

    @Test
    void taskProgressRenewsOnlyTheCurrentTenantLeaseOwner() throws Exception {
        Method method = TaskMapper.class.getMethod("updateRunningProgress",
                java.util.UUID.class, java.util.UUID.class, long.class, String.class,
                String.class, int.class, java.time.OffsetDateTime.class);
        assertThat(updateSql(method)).contains(
                "update tasks",
                "stage = #{stage}",
                "progress = #{progress}",
                "locked_until = #{lockeduntil}",
                "where tenant_id = #{tenantid}",
                "status = 'running'",
                "locked_by = #{workerid}",
                "version = #{version}");
    }

    @Test
    void inboxInsertIsTenantScopedAndIdempotentOnConsumerEvent() throws Exception {
        Method method = InboxEventMapper.class.getMethod("recordProcessed", InboxEventPo.class);
        String insertSql = insertSql(method);

        assertThat(insertSql).contains(
                "insert into inbox_events",
                "#{tenantid}",
                "#{consumername}",
                "#{eventid}",
                "on conflict on constraint uq_inbox_events_consumer_event",
                "do nothing");
    }

    @Test
    void inboxLookupIsTenantScoped() throws Exception {
        Method method = InboxEventMapper.class.getMethod(
                "selectProcessed", java.util.UUID.class, String.class, java.util.UUID.class);
        String sql = sql(method);

        assertThat(sql).contains(
                "from inbox_events",
                "tenant_id = #{tenantid}",
                "consumer_name = #{consumername}",
                "event_id = #{eventid}");
    }

    @Test
    void taskAndOutboxReadsAreTenantScoped() throws Exception {
        Method taskRead = TaskMapper.class.getMethod(
                "selectByIdAndTenant", java.util.UUID.class, java.util.UUID.class);
        assertThat(sql(taskRead)).contains("from tasks", "tenant_id = #{tenantid}", "id = #{id}");

        Method taskReadLock = TaskMapper.class.getMethod(
                "selectByIdAndTenantForUpdate", java.util.UUID.class, java.util.UUID.class);
        assertThat(sql(taskReadLock)).contains("from tasks", "tenant_id = #{tenantid}", "id = #{id}", "for update");

        Method outboxRead = OutboxEventMapper.class.getMethod(
                "selectByIdAndTenant", java.util.UUID.class, java.util.UUID.class);
        assertThat(sql(outboxRead)).contains("from outbox_events", "tenant_id = #{tenantid}", "id = #{id}");
    }

    private static boolean isBypassed(Method method) {
        InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
        return ignore != null && "1".equals(ignore.tenantLine());
    }

    /** Returns the raw SQL of an annotated statement, or null for inherited/plain methods. */
    private static String rawSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            return sql(method);
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            return updateSql(method);
        }
        Insert insert = method.getAnnotation(Insert.class);
        if (insert != null) {
            return insertSql(method);
        }
        return null;
    }

    private static String sql(Method method) {
        return normalize(method.getAnnotation(Select.class).value());
    }

    private static String updateSql(Method method) {
        return normalize(method.getAnnotation(Update.class).value());
    }

    private static String insertSql(Method method) {
        return normalize(method.getAnnotation(Insert.class).value());
    }

    private static String normalize(String[] lines) {
        return String.join(" ", Arrays.stream(lines).map(String::strip).toList())
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
