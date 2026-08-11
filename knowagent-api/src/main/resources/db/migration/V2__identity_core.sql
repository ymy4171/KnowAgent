CREATE TABLE tenants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT ck_tenants_slug CHECK (
        slug = lower(slug)
        AND slug ~ '^[a-z0-9][a-z0-9-]{0,62}$'
        AND right(slug, 1) <> '-'
    ),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT ck_tenants_settings_object CHECK (jsonb_typeof(settings) = 'object'),
    CONSTRAINT ck_tenants_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_tenants_slug_active
    ON tenants (slug)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_tenants_status
    ON tenants (status)
    WHERE deleted_at IS NULL;

CREATE TABLE departments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    parent_id uuid,
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    description varchar(512),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_departments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_departments_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_departments_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES departments (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_departments_code CHECK (
        code = lower(code)
        AND code ~ '^[a-z0-9][a-z0-9_-]{0,62}$'
    ),
    CONSTRAINT ck_departments_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_departments_parent_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_departments_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_departments_code_active
    ON departments (tenant_id, code)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_departments_parent_active
    ON departments (tenant_id, parent_id, sort_order, name)
    WHERE deleted_at IS NULL;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    department_id uuid,
    login_name varchar(128) NOT NULL,
    display_name varchar(128) NOT NULL,
    email varchar(320),
    phone_number varchar(32),
    avatar_object_key varchar(1024),
    password_hash varchar(512) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    login_failed_count integer NOT NULL DEFAULT 0,
    last_failed_login_at timestamptz,
    login_locked_until timestamptz,
    last_login_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_users_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_department FOREIGN KEY (tenant_id, department_id)
        REFERENCES departments (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_users_login_name CHECK (login_name = lower(login_name)),
    CONSTRAINT ck_users_email CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_users_login_failed_count CHECK (login_failed_count >= 0),
    CONSTRAINT ck_users_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_users_login_active
    ON users (tenant_id, login_name)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_users_email_active
    ON users (tenant_id, email)
    WHERE email IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_users_phone_active
    ON users (tenant_id, phone_number)
    WHERE phone_number IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_users_department_active
    ON users (tenant_id, department_id, display_name)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_users_status_active
    ON users (tenant_id, status)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE tenants IS 'Tenant root records. This is the only business table without tenant_id.';
COMMENT ON TABLE departments IS 'Tenant-scoped department hierarchy.';
COMMENT ON TABLE users IS 'Tenant-scoped local user identities and login state.';
