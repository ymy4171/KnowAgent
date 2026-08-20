package com.knowagent.observability.infrastructure.persistence.repository;

import com.knowagent.common.tenant.TenantId;
import com.knowagent.observability.application.port.out.InboxEventStore;
import com.knowagent.observability.infrastructure.persistence.converter.InboxEventPersistenceConverter;
import com.knowagent.observability.infrastructure.persistence.mapper.InboxEventMapper;
import com.knowagent.observability.inbox.InboxEvent;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Repository
public class MyBatisInboxEventStore implements InboxEventStore {

    private final InboxEventMapper mapper;

    public MyBatisInboxEventStore(InboxEventMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public boolean recordProcessed(InboxEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return mapper.recordProcessed(InboxEventPersistenceConverter.toPersistence(event)) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean wasProcessed(TenantId tenantId, String consumerName, UUID eventId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(consumerName, "consumerName must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        return mapper.selectProcessed(tenantId.value(), consumerName, eventId) != null;
    }
}
