CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    family_id uuid NOT NULL,
    parent_token_id uuid,
    token_hash char(64) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    revoke_reason varchar(256),
    issued_ip inet,
    user_agent varchar(512),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_refresh_tokens_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_refresh_tokens_family_member UNIQUE (tenant_id, id, family_id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_parent_family FOREIGN KEY (tenant_id, parent_token_id, family_id)
        REFERENCES refresh_tokens (tenant_id, id, family_id) ON DELETE RESTRICT,
    CONSTRAINT ck_refresh_tokens_root_family CHECK (parent_token_id IS NOT NULL OR family_id = id),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_tokens_lifecycle CHECK (
        (status = 'ACTIVE' AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (status = 'CONSUMED' AND consumed_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR status = 'EXPIRED'
    ),
    CONSTRAINT ck_refresh_tokens_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_refresh_tokens_one_child
    ON refresh_tokens (tenant_id, parent_token_id)
    WHERE parent_token_id IS NOT NULL;

CREATE INDEX ix_refresh_tokens_family
    ON refresh_tokens (tenant_id, family_id, status);

CREATE INDEX ix_refresh_tokens_user_active
    ON refresh_tokens (tenant_id, user_id, expires_at)
    WHERE status = 'ACTIVE';

CREATE TABLE api_keys (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    name varchar(128) NOT NULL,
    key_prefix varchar(16) NOT NULL,
    key_hash char(64) NOT NULL,
    scopes jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz,
    revoke_reason varchar(256),
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_keys_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash),
    CONSTRAINT fk_api_keys_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_keys_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_api_keys_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_api_keys_scopes_array CHECK (jsonb_typeof(scopes) = 'array'),
    CONSTRAINT ck_api_keys_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_api_keys_revocation CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status IN ('ACTIVE', 'EXPIRED') AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_api_keys_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_api_keys_user_name_active
    ON api_keys (tenant_id, user_id, lower(name))
    WHERE revoked_at IS NULL;

CREATE INDEX ix_api_keys_user_status
    ON api_keys (tenant_id, user_id, status, expires_at);

COMMENT ON TABLE refresh_tokens IS 'Hashed refresh tokens organized into rotation families for replay detection.';
COMMENT ON TABLE api_keys IS 'Hashed user API keys. Raw keys are never persisted.';
