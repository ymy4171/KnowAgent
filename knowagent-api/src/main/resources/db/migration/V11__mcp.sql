CREATE TABLE mcp_servers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    slug varchar(100) NOT NULL,
    name varchar(128) NOT NULL,
    description varchar(512),
    transport varchar(32) NOT NULL,
    endpoint_url varchar(1024) NOT NULL,
    auth_ciphertext text,
    secret_key_version integer,
    headers_ciphertext text,
    timeout_ms integer NOT NULL DEFAULT 30000,
    read_timeout_ms integer NOT NULL DEFAULT 300000,
    disabled_tools jsonb NOT NULL DEFAULT '[]'::jsonb,
    public_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid,
    updated_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_mcp_servers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_mcp_servers_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mcp_servers_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_mcp_servers_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_mcp_servers_slug CHECK (
        slug = lower(slug)
        AND slug ~ '^[a-z0-9][a-z0-9_-]{0,98}$'
    ),
    CONSTRAINT ck_mcp_servers_transport CHECK (transport IN ('SSE', 'STREAMABLE_HTTP')),
    CONSTRAINT ck_mcp_servers_secret_pair CHECK (
        (auth_ciphertext IS NULL AND headers_ciphertext IS NULL AND secret_key_version IS NULL)
        OR ((auth_ciphertext IS NOT NULL OR headers_ciphertext IS NOT NULL)
            AND secret_key_version IS NOT NULL AND secret_key_version > 0)
    ),
    CONSTRAINT ck_mcp_servers_timeouts CHECK (timeout_ms > 0 AND read_timeout_ms > 0),
    CONSTRAINT ck_mcp_servers_disabled_tools CHECK (jsonb_typeof(disabled_tools) = 'array'),
    CONSTRAINT ck_mcp_servers_public_config CHECK (jsonb_typeof(public_config) = 'object'),
    CONSTRAINT ck_mcp_servers_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_mcp_servers_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_mcp_servers_slug_active
    ON mcp_servers (tenant_id, slug)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_mcp_servers_status_active
    ON mcp_servers (tenant_id, status, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE agent_mcp_servers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    mcp_server_id uuid NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_mcp_servers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_mcp_servers_binding UNIQUE (tenant_id, agent_id, mcp_server_id),
    CONSTRAINT fk_agent_mcp_servers_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_mcp_servers_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_mcp_servers_server FOREIGN KEY (tenant_id, mcp_server_id)
        REFERENCES mcp_servers (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_mcp_servers_config CHECK (jsonb_typeof(config) = 'object')
);

CREATE INDEX ix_agent_mcp_servers_agent_enabled
    ON agent_mcp_servers (tenant_id, agent_id, enabled);

COMMENT ON TABLE mcp_servers IS 'Encrypted HTTP MCP server configuration. STDIO transport is deferred.';
COMMENT ON TABLE agent_mcp_servers IS 'Per-Agent MCP server activation and non-secret runtime options.';
