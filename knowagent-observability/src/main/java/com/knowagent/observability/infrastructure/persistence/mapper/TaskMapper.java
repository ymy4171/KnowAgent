package com.knowagent.observability.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.knowagent.observability.infrastructure.persistence.entity.TaskPo;
import com.knowagent.observability.task.TaskStatus;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistence for {@code tasks}.
 *
 * <p>Every statement here is explicitly tenant-scoped by {@code tenant_id = #{tenantId}}
 * and bypasses the tenant-line plugin ({@code @InterceptorIgnore(tenantLine = "1")}),
 * because the same mappers serve both authenticated requests and worker execution
 * (which has no {@code TenantContext}). The explicit tenant condition is the single
 * isolation mechanism and is locked by {@code ObservabilityMapperSqlContractTest}:
 * a bypassed statement without one fails the contract.
 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskPo> {

    String COLUMNS = """
            id, tenant_id, task_type, aggregate_type, aggregate_id, idempotency_key, status, stage,
            progress, payload, result, attempt_count, max_attempts, next_retry_at, locked_by, locked_until,
            cancel_requested_at, error_code, error_message, retryable, started_at, completed_at,
            version, created_at, updated_at
            """;

    @InterceptorIgnore(tenantLine = "1")
    @Results(id = "taskPoResultMap", value = {
            @Result(column = "payload", property = "payload", typeHandler = JsonNodeJsonbTypeHandler.class),
            @Result(column = "result", property = "result", typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM tasks
            WHERE tenant_id = #{tenantId} AND id = #{id}
            LIMIT 1
            """)
    TaskPo selectByIdAndTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @InterceptorIgnore(tenantLine = "1")
    @ResultMap("taskPoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM tasks
            WHERE tenant_id = #{tenantId} AND id = #{id}
            FOR UPDATE
            """)
    TaskPo selectByIdAndTenantForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * Claims one task: PENDING (or RUNNING with an expired lease) becomes RUNNING
     * with a fresh lease, clean attempt-visible state, and one more attempt. The
     * version guard plus the status/lease guard make the claim a single atomic
     * conditional write, and {@code attempt_count < max_attempts} stops an
     * exhausted task from being claimed again (which would otherwise trip the
     * {@code ck_tasks_attempts} CHECK and surface as a raw database error).
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE tasks
            SET status = 'RUNNING',
                stage = NULL,
                progress = 0,
                result = NULL,
                next_retry_at = NULL,
                locked_by = #{workerId},
                locked_until = #{lockedUntil},
                error_code = NULL,
                error_message = NULL,
                retryable = FALSE,
                started_at = COALESCE(started_at, #{now}),
                completed_at = NULL,
                attempt_count = attempt_count + 1,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND version = #{version}
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
              AND (status = 'PENDING'
                   OR (status = 'RUNNING' AND locked_until IS NOT NULL AND locked_until < #{now}))
              AND attempt_count < max_attempts
            """)
    int claimForExecution(@Param("tenantId") UUID tenantId,
                          @Param("id") UUID id,
                          @Param("version") long version,
                          @Param("workerId") String workerId,
                          @Param("now") OffsetDateTime now,
                          @Param("lockedUntil") OffsetDateTime lockedUntil);

    /** Renews one RUNNING task without releasing its execution lease. */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE tasks
            SET stage = #{stage},
                progress = #{progress},
                locked_until = #{lockedUntil},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND status = 'RUNNING'
              AND locked_by = #{workerId}
              AND version = #{version}
            """)
    int updateRunningProgress(@Param("tenantId") UUID tenantId,
                              @Param("id") UUID id,
                              @Param("version") long version,
                              @Param("workerId") String workerId,
                              @Param("stage") String stage,
                              @Param("progress") int progress,
                              @Param("lockedUntil") OffsetDateTime lockedUntil);

    /**
     * Applies one guarded state transition. The previous status and version gate
     * the write; a terminal target sets {@code completed_at} and clears the lease.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE tasks
            SET status = #{targetStatus},
                stage = #{stage},
                progress = #{progress},
                result = #{result, typeHandler=com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                retryable = #{retryable},
                next_retry_at = #{nextRetryAt},
                locked_by = NULL,
                locked_until = NULL,
                completed_at = CASE WHEN #{targetStatus} IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                                    THEN CURRENT_TIMESTAMP ELSE completed_at END,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND version = #{version}
              AND status = #{expectedStatus}
            """)
    int transitionTask(@Param("tenantId") UUID tenantId,
                       @Param("id") UUID id,
                       @Param("version") long version,
                       @Param("expectedStatus") TaskStatus expectedStatus,
                       @Param("targetStatus") TaskStatus targetStatus,
                       @Param("stage") String stage,
                       @Param("progress") int progress,
                       @Param("result") JsonNode result,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage,
                       @Param("retryable") boolean retryable,
                       @Param("nextRetryAt") OffsetDateTime nextRetryAt);
}
