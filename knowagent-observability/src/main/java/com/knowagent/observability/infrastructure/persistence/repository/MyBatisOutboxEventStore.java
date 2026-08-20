package com.knowagent.observability.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.OutboxEventStore;
import com.knowagent.observability.infrastructure.persistence.converter.OutboxEventPersistenceConverter;
import com.knowagent.observability.infrastructure.persistence.entity.OutboxEventPo;
import com.knowagent.observability.infrastructure.persistence.mapper.OutboxEventMapper;
import com.knowagent.observability.outbox.OutboxEvent;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisOutboxEventStore implements OutboxEventStore {

    private final OutboxEventMapper mapper;

    public MyBatisOutboxEventStore(OutboxEventMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void append(OutboxEvent event) {
        mapper.insert(OutboxEventPersistenceConverter.toPersistence(event));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OutboxEvent> findById(TenantId tenantId, UUID eventId) {
        OutboxEventPo row = mapper.selectByIdAndTenant(tenantId.value(), eventId);
        return Optional.ofNullable(row).map(OutboxEventPersistenceConverter::toDomain);
    }

    @Override
    @Transactional
    public List<OutboxEvent> claimReady(int limit, Instant now, String workerId, Duration lease) {
        List<OutboxEventPo> rows = mapper.selectClaimable(limit, offset(now));
        List<OutboxEvent> claimed = new ArrayList<>(rows.size());
        for (OutboxEventPo row : rows) {
            // We hold the FOR UPDATE SKIP LOCKED row lock, so the status/version
            // guard can only fail if the row vanished mid-claim; skip in that case.
            if (mapper.markProcessing(row.getTenantId(), row.getId(), row.getVersion(), workerId,
                    offset(now.plus(lease))) != 1) {
                continue;
            }
            claimed.add(OutboxEventPersistenceConverter.toDomain(row).claimed(workerId, now, lease));
        }
        return List.copyOf(claimed);
    }

    @Override
    @Transactional
    public int markPublished(OutboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return mapper.markPublished(event.tenantId().value(), event.id(), event.version());
    }

    @Override
    @Transactional
    public int markFailed(OutboxEvent current, OutboxEvent target) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return mapper.markFailed(current.tenantId().value(), current.id(), current.version(),
                target.status(), target.retryCount(), offset(target.nextRetryAt()), target.lastError());
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
