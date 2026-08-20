package com.knowagent.observability.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowagent.observability.infrastructure.persistence.entity.InboxEventPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * Persistence for {@code inbox_events}.
 *
 * <p>Receipts are written only by consumers, which run without a
 * {@code TenantContext}, so every statement bypasses the tenant-line plugin and is
 * explicitly tenant-scoped. Idempotency comes from the unique constraint
 * {@code uq_inbox_events_consumer_event (consumer_name, event_id)}: the insert is
 * {@code ON CONFLICT DO NOTHING}, returning 0 when the event was already received.
 */
@Mapper
public interface InboxEventMapper extends BaseMapper<InboxEventPo> {

    @InterceptorIgnore(tenantLine = "1")
    @Insert("""
            INSERT INTO inbox_events (id, tenant_id, consumer_name, event_id, event_type, payload_hash, processed_at)
            VALUES (#{id}, #{tenantId}, #{consumerName}, #{eventId}, #{eventType}, #{payloadHash}, CURRENT_TIMESTAMP)
            ON CONFLICT ON CONSTRAINT uq_inbox_events_consumer_event DO NOTHING
            """)
    int recordProcessed(InboxEventPo po);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT 1
            FROM inbox_events
            WHERE tenant_id = #{tenantId}
              AND consumer_name = #{consumerName}
              AND event_id = #{eventId}
            LIMIT 1
            """)
    Integer selectProcessed(@Param("tenantId") UUID tenantId,
                            @Param("consumerName") String consumerName,
                            @Param("eventId") UUID eventId);
}
