CREATE TABLE agents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    slug varchar(100) NOT NULL,
    name varchar(128) NOT NULL,
    description text,
    system_prompt text NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'DRAFT',
    model_provider_id uuid NOT NULL,
    chat_model varchar(255) NOT NULL,
    temperature numeric(3, 2) NOT NULL DEFAULT 0.70,
    max_output_tokens integer,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_default boolean NOT NULL DEFAULT false,
    created_by uuid,
    updated_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_agents_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_agents_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agents_model_provider FOREIGN KEY (tenant_id, model_provider_id)
        REFERENCES model_providers (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agents_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agents_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agents_slug CHECK (
        slug = lower(slug)
        AND slug ~ '^[a-z0-9][a-z0-9_-]{0,98}$'
    ),
    CONSTRAINT ck_agents_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_agents_temperature CHECK (temperature >= 0 AND temperature <= 2),
    CONSTRAINT ck_agents_max_tokens CHECK (max_output_tokens IS NULL OR max_output_tokens > 0),
    CONSTRAINT ck_agents_config_object CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT ck_agents_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_agents_slug_active
    ON agents (tenant_id, slug)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_agents_one_default_active
    ON agents (tenant_id)
    WHERE is_default = true AND status = 'ACTIVE' AND deleted_at IS NULL;

CREATE INDEX ix_agents_status_active
    ON agents (tenant_id, status, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE agent_knowledge_bases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    priority integer NOT NULL DEFAULT 0,
    retrieval_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_knowledge_bases_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_knowledge_bases_binding UNIQUE (tenant_id, agent_id, knowledge_base_id),
    CONSTRAINT fk_agent_knowledge_bases_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_knowledge_bases_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_knowledge_bases_kb FOREIGN KEY (tenant_id, knowledge_base_id)
        REFERENCES knowledge_bases (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_knowledge_bases_priority CHECK (priority >= 0),
    CONSTRAINT ck_agent_knowledge_bases_config CHECK (jsonb_typeof(retrieval_config) = 'object')
);

CREATE INDEX ix_agent_knowledge_bases_agent_priority
    ON agent_knowledge_bases (tenant_id, agent_id, priority, knowledge_base_id);

CREATE TABLE conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    title varchar(255),
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    is_pinned boolean NOT NULL DEFAULT false,
    next_message_sequence bigint NOT NULL DEFAULT 1,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_conversations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_conversations_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversations_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversations_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversations_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_conversations_next_sequence CHECK (next_message_sequence > 0),
    CONSTRAINT ck_conversations_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_conversations_version CHECK (version >= 0)
);

CREATE INDEX ix_conversations_user_recent
    ON conversations (tenant_id, user_id, is_pinned DESC, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_conversations_agent_active
    ON conversations (tenant_id, agent_id, updated_at DESC)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

CREATE TABLE messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    role varchar(32) NOT NULL,
    message_type varchar(32) NOT NULL DEFAULT 'TEXT',
    content text NOT NULL DEFAULT '',
    content_json jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    token_count integer,
    delivery_status varchar(32) NOT NULL DEFAULT 'COMPLETE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    CONSTRAINT uq_messages_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_messages_sequence UNIQUE (tenant_id, conversation_id, sequence_no),
    CONSTRAINT fk_messages_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_messages_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_messages_role CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    CONSTRAINT ck_messages_type CHECK (message_type IN ('TEXT', 'TOOL_CALL', 'TOOL_RESULT')),
    CONSTRAINT ck_messages_type_role CHECK (
        (message_type <> 'TOOL_CALL' OR role = 'ASSISTANT')
        AND (message_type <> 'TOOL_RESULT' OR role = 'TOOL')
    ),
    CONSTRAINT ck_messages_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_messages_tokens CHECK (token_count IS NULL OR token_count >= 0),
    CONSTRAINT ck_messages_delivery CHECK (delivery_status IN ('PENDING', 'STREAMING', 'COMPLETE', 'FAILED')),
    CONSTRAINT ck_messages_version CHECK (version >= 0)
);

CREATE INDEX ix_messages_conversation_sequence
    ON messages (tenant_id, conversation_id, sequence_no);

CREATE TABLE message_tool_calls (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    message_id uuid NOT NULL,
    tool_call_id varchar(128) NOT NULL,
    sequence_no integer NOT NULL,
    tool_name varchar(128) NOT NULL,
    arguments_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_content text,
    result_json jsonb,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    error_code varchar(128),
    error_message text,
    started_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_tool_calls_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_message_tool_calls_external_id UNIQUE (tenant_id, message_id, tool_call_id),
    CONSTRAINT uq_message_tool_calls_sequence UNIQUE (tenant_id, message_id, sequence_no),
    CONSTRAINT fk_message_tool_calls_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_tool_calls_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES messages (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_message_tool_calls_sequence CHECK (sequence_no >= 0),
    CONSTRAINT ck_message_tool_calls_arguments CHECK (jsonb_typeof(arguments_json) = 'object'),
    CONSTRAINT ck_message_tool_calls_status CHECK (status IN (
        'PENDING', 'RUNNING', 'APPROVAL_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_message_tool_calls_times CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at
    ),
    CONSTRAINT ck_message_tool_calls_version CHECK (version >= 0)
);

CREATE INDEX ix_message_tool_calls_status
    ON message_tool_calls (tenant_id, status, created_at);

CREATE TABLE message_citations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    message_id uuid NOT NULL,
    rank integer NOT NULL,
    knowledge_base_id uuid NOT NULL,
    file_id uuid NOT NULL,
    chunk_id uuid NOT NULL,
    chunk_index integer,
    source_name varchar(512) NOT NULL,
    page_number integer,
    score double precision,
    quoted_text text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_citations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_message_citations_rank UNIQUE (tenant_id, message_id, rank),
    CONSTRAINT fk_message_citations_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_citations_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES messages (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_message_citations_rank CHECK (rank > 0),
    CONSTRAINT ck_message_citations_chunk_index CHECK (chunk_index IS NULL OR chunk_index >= 0),
    CONSTRAINT ck_message_citations_page CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT ck_message_citations_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_message_citations_source
    ON message_citations (tenant_id, knowledge_base_id, file_id, chunk_id);

CREATE TABLE message_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    message_id uuid NOT NULL,
    user_id uuid NOT NULL,
    rating varchar(16) NOT NULL,
    reason text,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_feedback_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_message_feedback_user UNIQUE (tenant_id, message_id, user_id),
    CONSTRAINT fk_message_feedback_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_feedback_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES messages (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_message_feedback_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_message_feedback_rating CHECK (rating IN ('LIKE', 'DISLIKE')),
    CONSTRAINT ck_message_feedback_version CHECK (version >= 0)
);

COMMENT ON TABLE agents IS 'Tenant Agent definitions and model selection.';
COMMENT ON TABLE conversations IS 'Conversation roots and transactional message sequence allocator.';
COMMENT ON TABLE messages IS 'Ordered conversation messages; runtime links are added in V8.';
COMMENT ON TABLE message_tool_calls IS 'Ordered tool calls attached to assistant messages.';
COMMENT ON TABLE message_citations IS 'Immutable source snapshots; resource UUIDs are not live foreign keys so chunks can be rebuilt.';
COMMENT ON TABLE message_feedback IS 'One user rating per message.';
