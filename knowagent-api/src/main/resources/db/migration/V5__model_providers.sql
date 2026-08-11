CREATE TABLE model_providers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    provider_key varchar(100) NOT NULL,
    display_name varchar(128) NOT NULL,
    adapter_type varchar(64) NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    base_url varchar(1024) NOT NULL,
    embedding_base_url varchar(1024),
    rerank_base_url varchar(1024),
    secret_ciphertext text,
    secret_key_version integer,
    headers_ciphertext text,
    capabilities jsonb NOT NULL DEFAULT '[]'::jsonb,
    enabled_models jsonb NOT NULL DEFAULT '[]'::jsonb,
    public_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    enabled boolean NOT NULL DEFAULT true,
    health_status varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    config_version bigint NOT NULL DEFAULT 1,
    created_by uuid,
    updated_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_model_providers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_model_providers_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_providers_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_model_providers_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_model_providers_key CHECK (
        provider_key = lower(provider_key)
        AND provider_key ~ '^[a-z0-9][a-z0-9_-]{0,98}$'
    ),
    CONSTRAINT ck_model_providers_secret_pair CHECK (
        (secret_ciphertext IS NULL AND headers_ciphertext IS NULL AND secret_key_version IS NULL)
        OR ((secret_ciphertext IS NOT NULL OR headers_ciphertext IS NOT NULL)
            AND secret_key_version IS NOT NULL AND secret_key_version > 0)
    ),
    CONSTRAINT ck_model_providers_capabilities_array CHECK (jsonb_typeof(capabilities) = 'array'),
    CONSTRAINT ck_model_providers_models_array CHECK (jsonb_typeof(enabled_models) = 'array'),
    CONSTRAINT ck_model_providers_public_config_object CHECK (jsonb_typeof(public_config) = 'object'),
    CONSTRAINT ck_model_providers_health CHECK (health_status IN ('UNKNOWN', 'HEALTHY', 'UNHEALTHY')),
    CONSTRAINT ck_model_providers_config_version CHECK (config_version > 0),
    CONSTRAINT ck_model_providers_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_model_providers_key_active
    ON model_providers (tenant_id, provider_key)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_model_providers_enabled_active
    ON model_providers (tenant_id, enabled, health_status)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE model_providers IS 'Encrypted tenant model provider configuration and enabled model catalog.';
