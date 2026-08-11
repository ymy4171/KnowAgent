CREATE TABLE roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    description varchar(512),
    permissions jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_system boolean NOT NULL DEFAULT false,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_roles_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_roles_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_roles_code CHECK (
        code = upper(code)
        AND code ~ '^[A-Z][A-Z0-9_]{0,62}$'
    ),
    CONSTRAINT ck_roles_permissions_array CHECK (jsonb_typeof(permissions) = 'array'),
    CONSTRAINT ck_roles_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_roles_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_roles_code_active
    ON roles (tenant_id, code)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_roles_status_active
    ON roles (tenant_id, status)
    WHERE deleted_at IS NULL;

CREATE TABLE user_roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    granted_by uuid,
    granted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz,
    CONSTRAINT uq_user_roles_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_user_roles_assignment UNIQUE (tenant_id, user_id, role_id),
    CONSTRAINT fk_user_roles_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES roles (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (tenant_id, granted_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_roles_expiry CHECK (expires_at IS NULL OR expires_at > granted_at)
);

CREATE INDEX ix_user_roles_user
    ON user_roles (tenant_id, user_id, expires_at);

CREATE INDEX ix_user_roles_role
    ON user_roles (tenant_id, role_id);

COMMENT ON TABLE roles IS 'Tenant-scoped RBAC roles with a JSON permission code array.';
COMMENT ON TABLE user_roles IS 'Physical user-to-role assignments within one tenant.';
