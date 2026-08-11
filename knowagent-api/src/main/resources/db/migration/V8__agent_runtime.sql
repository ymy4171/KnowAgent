CREATE TABLE agent_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    user_id uuid NOT NULL,
    input_message_id uuid NOT NULL,
    output_message_id uuid,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    run_type varchar(32) NOT NULL DEFAULT 'CHAT',
    source varchar(32) NOT NULL DEFAULT 'CHAT',
    channel varchar(32) NOT NULL DEFAULT 'WEB',
    input_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    origin_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_event_cursor varchar(128),
    worker_id varchar(128),
    lease_expires_at timestamptz,
    cancel_requested_at timestamptz,
    error_code varchar(128),
    error_message text,
    started_at timestamptz,
    finished_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_runs_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_agent_runs_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runs_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runs_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runs_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runs_input_message FOREIGN KEY (tenant_id, input_message_id)
        REFERENCES messages (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runs_output_message FOREIGN KEY (tenant_id, output_message_id)
        REFERENCES messages (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runs_status CHECK (status IN (
        'PENDING', 'RUNNING', 'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_agent_runs_type CHECK (run_type IN ('CHAT', 'RESUME', 'TOOL')),
    CONSTRAINT ck_agent_runs_source CHECK (source IN ('CHAT', 'AGENT_CALL', 'EVALUATION')),
    CONSTRAINT ck_agent_runs_channel CHECK (channel IN ('WEB', 'API', 'INTERNAL')),
    CONSTRAINT ck_agent_runs_input_payload CHECK (jsonb_typeof(input_payload) = 'object'),
    CONSTRAINT ck_agent_runs_origin_metadata CHECK (jsonb_typeof(origin_metadata) = 'object'),
    CONSTRAINT ck_agent_runs_lease CHECK (
        (worker_id IS NULL AND lease_expires_at IS NULL)
        OR (worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_agent_runs_times CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    ),
    CONSTRAINT ck_agent_runs_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_run_active_per_conversation
    ON agent_runs (tenant_id, conversation_id)
    WHERE status IN ('RUNNING', 'INTERRUPTED');

CREATE INDEX ix_agent_runs_conversation_status
    ON agent_runs (tenant_id, conversation_id, status, created_at DESC);

CREATE INDEX ix_agent_runs_worker_lease
    ON agent_runs (tenant_id, lease_expires_at)
    WHERE status = 'RUNNING';

CREATE TABLE agent_run_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    queue_sequence bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    user_id uuid NOT NULL,
    run_id uuid NOT NULL,
    input_message_id uuid NOT NULL,
    source varchar(32) NOT NULL DEFAULT 'CHAT',
    channel varchar(32) NOT NULL DEFAULT 'WEB',
    external_id varchar(128),
    queue_policy varchar(32) NOT NULL DEFAULT 'ENQUEUE',
    status varchar(32) NOT NULL DEFAULT 'QUEUED',
    input_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    origin_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(128),
    error_message text,
    dispatched_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_run_requests_queue_sequence UNIQUE (queue_sequence),
    CONSTRAINT uq_agent_run_requests_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_run_requests_run UNIQUE (tenant_id, run_id),
    CONSTRAINT fk_agent_run_requests_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_requests_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_requests_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_requests_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_requests_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_runs (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_requests_message FOREIGN KEY (tenant_id, input_message_id)
        REFERENCES messages (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_run_requests_source CHECK (source IN ('CHAT', 'AGENT_CALL', 'EVALUATION')),
    CONSTRAINT ck_agent_run_requests_channel CHECK (channel IN ('WEB', 'API', 'INTERNAL')),
    CONSTRAINT ck_agent_run_requests_policy CHECK (queue_policy IN ('ENQUEUE', 'REJECT')),
    CONSTRAINT ck_agent_run_requests_status CHECK (status IN (
        'QUEUED', 'DISPATCHED', 'CANCELLED', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT ck_agent_run_requests_input_payload CHECK (jsonb_typeof(input_payload) = 'object'),
    CONSTRAINT ck_agent_run_requests_origin_metadata CHECK (jsonb_typeof(origin_metadata) = 'object'),
    CONSTRAINT ck_agent_run_requests_dispatch_time CHECK (
        (status = 'DISPATCHED' AND dispatched_at IS NOT NULL)
        OR status <> 'DISPATCHED'
    ),
    CONSTRAINT ck_agent_run_requests_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_agent_run_requests_external
    ON agent_run_requests (tenant_id, source, channel, external_id)
    WHERE external_id IS NOT NULL;

CREATE INDEX ix_agent_run_requests_fifo
    ON agent_run_requests (tenant_id, conversation_id, status, queue_sequence)
    WHERE status = 'QUEUED';

ALTER TABLE messages
    ADD COLUMN run_id uuid,
    ADD COLUMN request_id uuid;

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_runs (tenant_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_messages_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES agent_run_requests (tenant_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_messages_run
    ON messages (tenant_id, run_id, sequence_no)
    WHERE run_id IS NOT NULL;

CREATE INDEX ix_messages_request
    ON messages (tenant_id, request_id)
    WHERE request_id IS NOT NULL;

CREATE TABLE agent_run_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    event_type varchar(64) NOT NULL,
    data text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL,
    stream_cursor varchar(128),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_run_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_run_events_sequence UNIQUE (tenant_id, run_id, sequence_no),
    CONSTRAINT fk_agent_run_events_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_events_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_runs (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_run_events_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_run_events_type CHECK (event_type IN (
        'RUN_STARTED', 'MODEL_DELTA', 'TOOL_STARTED', 'TOOL_COMPLETED',
        'APPROVAL_REQUIRED', 'RUN_INTERRUPTED', 'RUN_COMPLETED',
        'RUN_FAILED', 'RUN_CANCELLED'
    )),
    CONSTRAINT ck_agent_run_events_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_agent_run_events_cursor
    ON agent_run_events (tenant_id, run_id, stream_cursor)
    WHERE stream_cursor IS NOT NULL;

CREATE INDEX ix_agent_run_events_replay
    ON agent_run_events (tenant_id, run_id, sequence_no, occurred_at);

CREATE TABLE agent_checkpoints (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    stage varchar(64) NOT NULL,
    checkpoint_type varchar(32) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    caused_by_event_id uuid,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_checkpoints_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_checkpoints_sequence UNIQUE (tenant_id, run_id, sequence_no),
    CONSTRAINT fk_agent_checkpoints_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_checkpoints_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_runs (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_checkpoints_event FOREIGN KEY (tenant_id, caused_by_event_id)
        REFERENCES agent_run_events (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_checkpoints_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_checkpoints_type CHECK (checkpoint_type IN ('STAGE', 'INTERRUPTION', 'MANUAL')),
    CONSTRAINT ck_agent_checkpoints_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_agent_checkpoints_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX ix_agent_checkpoints_latest
    ON agent_checkpoints (tenant_id, run_id, sequence_no DESC);

COMMENT ON TABLE agent_runs IS 'PostgreSQL source of truth for Agent execution state.';
COMMENT ON TABLE agent_run_requests IS 'FIFO request queue with UUID idempotency keys.';
COMMENT ON TABLE agent_run_events IS 'Append-only durable Run event index used when Redis replay expires.';
COMMENT ON TABLE agent_checkpoints IS 'Versioned structured checkpoint envelope with a JSONB recovery payload.';
