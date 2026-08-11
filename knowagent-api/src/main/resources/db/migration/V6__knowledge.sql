CREATE TABLE knowledge_bases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    slug varchar(100) NOT NULL,
    name varchar(255) NOT NULL,
    description text,
    knowledge_type varchar(32) NOT NULL DEFAULT 'LOCAL',
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    embedding_provider_id uuid,
    embedding_model varchar(255),
    rerank_provider_id uuid,
    rerank_model varchar(255),
    chunk_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    retrieval_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid,
    updated_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_knowledge_bases_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_knowledge_bases_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_bases_embedding_provider FOREIGN KEY (tenant_id, embedding_provider_id)
        REFERENCES model_providers (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_bases_rerank_provider FOREIGN KEY (tenant_id, rerank_provider_id)
        REFERENCES model_providers (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_bases_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_bases_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_bases_slug CHECK (
        slug = lower(slug)
        AND slug ~ '^[a-z0-9][a-z0-9_-]{0,98}$'
    ),
    CONSTRAINT ck_knowledge_bases_type CHECK (knowledge_type IN ('LOCAL', 'EXTERNAL')),
    CONSTRAINT ck_knowledge_bases_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETING', 'DELETED')),
    CONSTRAINT ck_knowledge_bases_embedding_pair CHECK (
        (embedding_provider_id IS NULL AND embedding_model IS NULL)
        OR (embedding_provider_id IS NOT NULL AND embedding_model IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_bases_rerank_pair CHECK (
        (rerank_provider_id IS NULL AND rerank_model IS NULL)
        OR (rerank_provider_id IS NOT NULL AND rerank_model IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_bases_chunk_policy CHECK (jsonb_typeof(chunk_policy) = 'object'),
    CONSTRAINT ck_knowledge_bases_retrieval_config CHECK (jsonb_typeof(retrieval_config) = 'object'),
    CONSTRAINT ck_knowledge_bases_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_knowledge_bases_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_knowledge_bases_slug_active
    ON knowledge_bases (tenant_id, slug)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_knowledge_bases_status_active
    ON knowledge_bases (tenant_id, status, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE knowledge_files (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    parent_file_id uuid,
    upload_idempotency_key varchar(128),
    display_name varchar(512) NOT NULL,
    original_filename varchar(512) NOT NULL,
    object_key varchar(1024) NOT NULL,
    content_type varchar(255) NOT NULL,
    file_extension varchar(32),
    sha256 char(64) NOT NULL,
    file_size_bytes bigint NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'UPLOADED',
    chunk_count integer NOT NULL DEFAULT 0,
    token_count bigint NOT NULL DEFAULT 0,
    processing_params jsonb NOT NULL DEFAULT '{}'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(128),
    error_message text,
    retryable boolean NOT NULL DEFAULT false,
    created_by uuid,
    updated_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT uq_knowledge_files_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_files_kb_id UNIQUE (tenant_id, knowledge_base_id, id),
    CONSTRAINT uq_knowledge_files_object_key UNIQUE (tenant_id, object_key),
    CONSTRAINT fk_knowledge_files_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_files_kb FOREIGN KEY (tenant_id, knowledge_base_id)
        REFERENCES knowledge_bases (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_files_parent FOREIGN KEY (tenant_id, knowledge_base_id, parent_file_id)
        REFERENCES knowledge_files (tenant_id, knowledge_base_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_files_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_files_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_knowledge_files_parent_self CHECK (parent_file_id IS NULL OR parent_file_id <> id),
    CONSTRAINT ck_knowledge_files_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_files_size CHECK (file_size_bytes >= 0),
    CONSTRAINT ck_knowledge_files_counts CHECK (chunk_count >= 0 AND token_count >= 0),
    CONSTRAINT ck_knowledge_files_status CHECK (status IN (
        'UPLOADED', 'QUEUED', 'PARSING', 'CHUNKING', 'EMBEDDING',
        'INDEXING', 'READY', 'FAILED', 'DELETING', 'DELETED'
    )),
    CONSTRAINT ck_knowledge_files_deleted CHECK (status <> 'DELETED' OR deleted_at IS NOT NULL),
    CONSTRAINT ck_knowledge_files_processing_params CHECK (jsonb_typeof(processing_params) = 'object'),
    CONSTRAINT ck_knowledge_files_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_knowledge_files_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_knowledge_files_upload_idempotency
    ON knowledge_files (tenant_id, knowledge_base_id, upload_idempotency_key)
    WHERE upload_idempotency_key IS NOT NULL;

CREATE INDEX ix_knowledge_files_kb_status
    ON knowledge_files (tenant_id, knowledge_base_id, status, created_at DESC);

CREATE INDEX ix_knowledge_files_hash
    ON knowledge_files (tenant_id, knowledge_base_id, sha256);

CREATE INDEX ix_knowledge_files_deletion
    ON knowledge_files (tenant_id, status, updated_at)
    WHERE status = 'DELETING';

CREATE TABLE knowledge_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    file_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    content_hash char(64) NOT NULL,
    token_count integer NOT NULL DEFAULT 0,
    start_char_offset integer,
    end_char_offset integer,
    start_token_offset integer,
    end_token_offset integer,
    page_number integer,
    section_path jsonb NOT NULL DEFAULT '[]'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    index_status varchar(32) NOT NULL DEFAULT 'PENDING',
    embedding_model_spec varchar(512),
    error_code varchar(128),
    error_message text,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_chunks_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_chunks_position UNIQUE (tenant_id, file_id, chunk_index),
    CONSTRAINT fk_knowledge_chunks_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_chunks_kb FOREIGN KEY (tenant_id, knowledge_base_id)
        REFERENCES knowledge_bases (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_chunks_file FOREIGN KEY (tenant_id, knowledge_base_id, file_id)
        REFERENCES knowledge_files (tenant_id, knowledge_base_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_chunks_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_knowledge_chunks_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_chunks_tokens CHECK (token_count >= 0),
    CONSTRAINT ck_knowledge_chunks_char_offsets CHECK (
        (start_char_offset IS NULL AND end_char_offset IS NULL)
        OR (start_char_offset IS NOT NULL AND end_char_offset IS NOT NULL
            AND start_char_offset >= 0 AND end_char_offset >= start_char_offset)
    ),
    CONSTRAINT ck_knowledge_chunks_token_offsets CHECK (
        (start_token_offset IS NULL AND end_token_offset IS NULL)
        OR (start_token_offset IS NOT NULL AND end_token_offset IS NOT NULL
            AND start_token_offset >= 0 AND end_token_offset >= start_token_offset)
    ),
    CONSTRAINT ck_knowledge_chunks_page CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT ck_knowledge_chunks_section_path CHECK (jsonb_typeof(section_path) = 'array'),
    CONSTRAINT ck_knowledge_chunks_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_knowledge_chunks_status CHECK (index_status IN ('PENDING', 'INDEXING', 'READY', 'FAILED')),
    CONSTRAINT ck_knowledge_chunks_version CHECK (version >= 0)
);

CREATE INDEX ix_knowledge_chunks_file
    ON knowledge_chunks (tenant_id, file_id, chunk_index);

CREATE INDEX ix_knowledge_chunks_kb_status
    ON knowledge_chunks (tenant_id, knowledge_base_id, index_status);

COMMENT ON TABLE knowledge_bases IS 'Tenant knowledge base configuration and retrieval policy.';
COMMENT ON TABLE knowledge_files IS 'MinIO-backed source file metadata and ingestion lifecycle.';
COMMENT ON TABLE knowledge_chunks IS 'Rebuildable text chunks whose UUID is reused as the Milvus entity ID.';
