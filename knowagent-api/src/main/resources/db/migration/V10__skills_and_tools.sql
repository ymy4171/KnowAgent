CREATE TABLE skills (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    slug varchar(128) NOT NULL,
    name varchar(128) NOT NULL,
    description text NOT NULL,
    source_type varchar(32) NOT NULL DEFAULT 'UPLOAD',
    version_name varchar(64),
    content_hash char(64),
    object_key varchar(1024),
    manifest jsonb NOT NULL DEFAULT '{}'::jsonb,
    tool_dependencies jsonb NOT NULL DEFAULT '[]'::jsonb,
    enabled boolean NOT NULL DEFAULT true,
    created_by uuid,
    updated_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_skills_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_skills_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_skills_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_skills_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_skills_slug CHECK (
        slug = lower(slug)
        AND slug ~ '^[a-z0-9][a-z0-9_-]{0,126}$'
    ),
    CONSTRAINT ck_skills_source CHECK (source_type IN ('BUILTIN', 'UPLOAD', 'REMOTE')),
    CONSTRAINT ck_skills_content_hash CHECK (
        content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_skills_manifest CHECK (jsonb_typeof(manifest) = 'object'),
    CONSTRAINT ck_skills_tool_dependencies CHECK (jsonb_typeof(tool_dependencies) = 'array'),
    CONSTRAINT ck_skills_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_skills_slug_active
    ON skills (tenant_id, slug)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_skills_enabled_active
    ON skills (tenant_id, enabled, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE agent_skills (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_skills_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_skills_binding UNIQUE (tenant_id, agent_id, skill_id),
    CONSTRAINT fk_agent_skills_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_skills_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_skills_skill FOREIGN KEY (tenant_id, skill_id)
        REFERENCES skills (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_skills_config CHECK (jsonb_typeof(config) = 'object')
);

CREATE INDEX ix_agent_skills_agent_enabled
    ON agent_skills (tenant_id, agent_id, enabled);

CREATE TABLE agent_tool_grants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    tool_name varchar(128) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    approval_mode varchar(32) NOT NULL DEFAULT 'NEVER',
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_tool_grants_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_agent_tool_grants_tool UNIQUE (tenant_id, agent_id, tool_name),
    CONSTRAINT fk_agent_tool_grants_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_tool_grants_agent FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agents (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_tool_grants_approval CHECK (approval_mode IN ('NEVER', 'ALWAYS', 'ON_RISK')),
    CONSTRAINT ck_agent_tool_grants_config CHECK (jsonb_typeof(config) = 'object')
);

CREATE INDEX ix_agent_tool_grants_agent_enabled
    ON agent_tool_grants (tenant_id, agent_id, enabled);

COMMENT ON TABLE skills IS 'Basic tenant Skill registry; full installation and dependency resolution are deferred.';
COMMENT ON TABLE agent_skills IS 'Agent-to-Skill activation and configuration.';
COMMENT ON TABLE agent_tool_grants IS 'Per-Agent built-in tool allowlist and approval policy.';
