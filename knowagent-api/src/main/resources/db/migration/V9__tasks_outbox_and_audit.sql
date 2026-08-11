CREATE TABLE tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    task_type varchar(64) NOT NULL,
    aggregate_type varchar(64),
    aggregate_id varchar(128),
    idempotency_key varchar(128),
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    stage varchar(64),
    progress smallint NOT NULL DEFAULT 0,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    result jsonb,
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    next_retry_at timestamptz,
    locked_by varchar(128),
    locked_until timestamptz,
    cancel_requested_at timestamptz,
    error_code varchar(128),
    error_message text,
    retryable boolean NOT NULL DEFAULT false,
    started_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tasks_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_tasks_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tasks_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_tasks_progress CHECK (progress >= 0 AND progress <= 100),
    CONSTRAINT ck_tasks_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_tasks_attempts CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_tasks_lock CHECK (
        (locked_by IS NULL AND locked_until IS NULL)
        OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)
    ),
    CONSTRAINT ck_tasks_times CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at
    ),
    CONSTRAINT ck_tasks_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_tasks_idempotency
    ON tasks (tenant_id, task_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_tasks_dispatchable
    ON tasks (tenant_id, status, next_retry_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_tasks_aggregate
    ON tasks (tenant_id, aggregate_type, aggregate_id, created_at DESC);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    event_type varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    headers jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    retry_count integer NOT NULL DEFAULT 0,
    max_retries integer NOT NULL DEFAULT 10,
    next_retry_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by varchar(128),
    locked_until timestamptz,
    last_error text,
    published_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_outbox_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_outbox_events_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbox_events_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_events_headers CHECK (jsonb_typeof(headers) = 'object'),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD_LETTER')),
    CONSTRAINT ck_outbox_events_retries CHECK (
        retry_count >= 0 AND max_retries > 0 AND retry_count <= max_retries
    ),
    CONSTRAINT ck_outbox_events_lock CHECK (
        (locked_by IS NULL AND locked_until IS NULL)
        OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)
    ),
    CONSTRAINT ck_outbox_events_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR status <> 'PUBLISHED'
    ),
    CONSTRAINT ck_outbox_events_version CHECK (version >= 0)
);

CREATE INDEX ix_outbox_events_publishable
    ON outbox_events (next_retry_at, locked_until, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX ix_outbox_events_backlog
    ON outbox_events (tenant_id, status, created_at);

CREATE TABLE inbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    consumer_name varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    payload_hash char(64),
    processed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inbox_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inbox_events_consumer_event UNIQUE (consumer_name, event_id),
    CONSTRAINT fk_inbox_events_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_inbox_events_payload_hash CHECK (
        payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_inbox_events_tenant_processed
    ON inbox_events (tenant_id, processed_at DESC);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    actor_user_id uuid,
    action varchar(128) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id varchar(128),
    outcome varchar(32) NOT NULL,
    ip_address inet,
    user_agent varchar(512),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_audit_logs_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_audit_logs_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (tenant_id, actor_user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_logs_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_audit_logs_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_audit_logs_tenant_time
    ON audit_logs (tenant_id, occurred_at DESC);

CREATE INDEX ix_audit_logs_actor_time
    ON audit_logs (tenant_id, actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX ix_audit_logs_resource
    ON audit_logs (tenant_id, resource_type, resource_id, occurred_at DESC);

COMMENT ON TABLE tasks IS 'Durable user-visible asynchronous task state.';
COMMENT ON TABLE outbox_events IS 'Transactional events published to Redis Streams by competing publishers.';
COMMENT ON TABLE inbox_events IS 'Successful consumer event receipts used for idempotency.';
COMMENT ON TABLE audit_logs IS 'Append-only tenant audit trail.';
