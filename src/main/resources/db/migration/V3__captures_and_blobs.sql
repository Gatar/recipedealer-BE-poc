-- A row exists only after the blob file has been written and fsynced.
CREATE TABLE blob (
    sha256     char(64) PRIMARY KEY,
    size_bytes bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Immutable raw input. The original JSON bytes are the blob body_sha256; body is a copy for querying.
CREATE TABLE capture (
    id             uuid PRIMARY KEY,
    received_at    timestamptz NOT NULL DEFAULT now(),
    captured_at    timestamptz,
    user_id        uuid        NOT NULL REFERENCES app_user (id),
    device_id      uuid        NOT NULL REFERENCES device_token (id),
    schema_version int         NOT NULL,
    body_sha256    char(64)    NOT NULL REFERENCES blob (sha256),
    body           jsonb       NOT NULL,
    source_type    text        NOT NULL,
    source_url     text
);

CREATE TABLE capture_attachment (
    capture_id         uuid     NOT NULL REFERENCES capture (id),
    name               text     NOT NULL,
    role               text     NOT NULL,
    media_type         text     NOT NULL,
    original_file_name text,
    blob_sha256        char(64) NOT NULL REFERENCES blob (sha256),
    size_bytes         bigint   NOT NULL,
    width              int,
    height             int,
    PRIMARY KEY (capture_id, name)
);

CREATE FUNCTION rd_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER capture_immutable BEFORE UPDATE OR DELETE ON capture
    FOR EACH ROW EXECUTE FUNCTION rd_reject_change();
CREATE TRIGGER capture_attachment_immutable BEFORE UPDATE OR DELETE ON capture_attachment
    FOR EACH ROW EXECUTE FUNCTION rd_reject_change();

-- Mutable processing status, kept apart from the immutable capture.
CREATE TABLE capture_state (
    capture_id uuid PRIMARY KEY REFERENCES capture (id),
    status     text        NOT NULL
        CHECK (status IN ('accepted', 'processing', 'needs_decision', 'needs_attention', 'done')),
    message    text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX capture_state_status_idx ON capture_state (status, updated_at);
