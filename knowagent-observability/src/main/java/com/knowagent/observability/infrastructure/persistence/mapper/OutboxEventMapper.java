package com.knowagent.observability.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.observability.infrastructure.persistence.entity.OutboxEventPo;
import com.knowagent.observability.outbox.OutboxStatus;
import com.knowagent.security.infrastructure.persistence.typehandler.JsonNodeJsonbTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code outbox_events}.
 *
 * <p>Like {@link TaskMapper}, every statement bypasses the tenant-line plugin and is
 * explicitly tenant-scoped except one documented exception: {@link #selectClaimable}
 * is the competing-publisher claim, which by design spans <em>all</em> tenants (the
 * {@code ix_outbox_events_publishable} index is global). Each claimed row carries its
 * {@code tenant_id}, which every downstream worker operation re-applies in its own
 * {@code WHERE}. {@code ObservabilityMapperSqlContractTest} locks the exact bypass
 * set and the single exception.
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventPo> {

    String COLUMNS = """
            id, tenant_id, aggregate_type, aggregate_id, event_type, payload, headers, status,
            retry_count, max_retries, next_retry_at, locked_by, locked_until, last_error,
            published_at, version, created_at
            """;

    @InterceptorIgnore(tenantLine = "1")
    @Results(id = "outboxEventPoResultMap", value = {
            @Result(column = "payload", property = "payload", typeHandler = JsonNodeJsonbTypeHandler.class),
            @Result(column = "headers", property = "headers", typeHandler = JsonNodeJsonbTypeHandler.class)
    })
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM outbox_events
            WHERE tenant_id = #{tenantId} AND id = #{id}
            LIMIT 1
            """)
    OutboxEventPo selectByIdAndTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * Claims up to {@code limit} ready events. {@code PENDING} events are ready when
     * {@code next_retry_at} has passed; {@code PROCESSING} events are reclaimable
     * only when their lease expired. Ordered by {@code next_retry_at} then
     * {@code created_at} (the {@code ix_outbox_events_publishable} index), with
     * {@code FOR UPDATE SKIP LOCKED} so competing publishers never receive the same
     * event. Cross-tenant by design - see the class Javadoc.
     */
    @InterceptorIgnore(tenantLine = "1")
    @ResultMap("outboxEventPoResultMap")
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM outbox_events
            WHERE next_retry_at <= #{now}
              AND (status = 'PENDING'
                   OR (status = 'PROCESSING' AND locked_until IS NOT NULL AND locked_until < #{now}))
            ORDER BY next_retry_at, created_at
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEventPo> selectClaimable(@Param("limit") int limit, @Param("now") OffsetDateTime now);

    /**
     * Applies the claim lease to one locked row. The status/version guard is
     * belt-and-suspenders: the row lock already serialises claims, but a crash right
     * after the lock must never leave a half-claimed event.
     */
    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE outbox_events
            SET status = 'PROCESSING',
                locked_by = #{workerId},
                locked_until = #{lockedUntil},
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND status IN ('PENDING', 'PROCESSING')
              AND version = #{version}
            """)
    int markProcessing(@Param("tenantId") UUID tenantId,
                       @Param("id") UUID id,
                       @Param("version") long version,
                       @Param("workerId") String workerId,
                       @Param("lockedUntil") OffsetDateTime lockedUntil);

    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = CURRENT_TIMESTAMP,
                locked_by = NULL,
                locked_until = NULL,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND status = 'PROCESSING'
              AND version = #{version}
            """)
    int markPublished(@Param("tenantId") UUID tenantId,
                      @Param("id") UUID id,
                      @Param("version") long version);

    @InterceptorIgnore(tenantLine = "1")
    @Update("""
            UPDATE outbox_events
            SET status = #{targetStatus},
                retry_count = #{retryCount},
                next_retry_at = #{nextRetryAt},
                last_error = #{lastError},
                locked_by = NULL,
                locked_until = NULL,
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND status = 'PROCESSING'
              AND version = #{version}
            """)
    int markFailed(@Param("tenantId") UUID tenantId,
                   @Param("id") UUID id,
                   @Param("version") long version,
                   @Param("targetStatus") OutboxStatus targetStatus,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryAt") OffsetDateTime nextRetryAt,
                   @Param("lastError") String lastError);
}
