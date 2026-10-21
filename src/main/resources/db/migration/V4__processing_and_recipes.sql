-- Job queue: claimed with FOR UPDATE SKIP LOCKED; a running job whose lease expired is claimed again.
CREATE TABLE job (
    id              uuid PRIMARY KEY,
    kind            text        NOT NULL,
    capture_id      uuid        NOT NULL REFERENCES capture (id),
    status          text        NOT NULL CHECK (status IN ('queued', 'running', 'done', 'needs_attention')),
    attempts        int         NOT NULL DEFAULT 0,
    max_attempts    int         NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    locked_until    timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX job_ready_idx ON job (next_attempt_at) WHERE status = 'queued';
CREATE INDEX job_running_idx ON job (locked_until) WHERE status = 'running';
CREATE INDEX job_capture_idx ON job (capture_id);

-- One processing of one capture. Never overwritten; reprocessing creates a new run.
CREATE TABLE processing_run (
    id                uuid PRIMARY KEY,
    capture_id        uuid        NOT NULL REFERENCES capture (id),
    extractor_name    text        NOT NULL,
    extractor_version text        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('running', 'succeeded', 'awaiting_extraction', 'failed')),
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    error             text
);
CREATE INDEX processing_run_capture_idx ON processing_run (capture_id, started_at);

CREATE TABLE recipe_candidate (
    run_id       uuid    NOT NULL REFERENCES processing_run (id),
    idx          int     NOT NULL,
    content      jsonb   NOT NULL,
    content_hash char(64) NOT NULL,
    provenance   jsonb   NOT NULL,
    needs_review boolean NOT NULL,
    keys         jsonb   NOT NULL,
    PRIMARY KEY (run_id, idx)
);

CREATE TABLE recipe (
    id               uuid PRIMARY KEY,
    head_revision_id uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    merged_into      uuid REFERENCES recipe (id),
    deleted_at       timestamptz,
    -- Copied from the head revision on every head move (generated columns cannot read other tables).
    title            text,
    ingredients_text text,
    source_summary   text,
    thumbnail_blob   char(64) REFERENCES blob (sha256),
    title_norm       text GENERATED ALWAYS AS (rd_normalize(coalesce(title, ''))) STORED,
    search_text      text GENERATED ALWAYS AS (
        rd_normalize(coalesce(title, '') || ' ' || coalesce(ingredients_text, ''))) STORED
);
CREATE INDEX recipe_title_trgm_idx ON recipe USING gin (title_norm gin_trgm_ops);
CREATE INDEX recipe_search_trgm_idx ON recipe USING gin (search_text gin_trgm_ops);
CREATE INDEX recipe_sync_idx ON recipe (updated_at, id);

-- Immutable recipe content.
CREATE TABLE recipe_revision (
    id            uuid PRIMARY KEY,
    recipe_id     uuid        NOT NULL REFERENCES recipe (id),
    parent_id     uuid REFERENCES recipe_revision (id),
    origin        text        NOT NULL CHECK (origin IN ('MACHINE', 'USER')),
    run_id        uuid REFERENCES processing_run (id),
    capture_id    uuid REFERENCES capture (id),
    user_id       uuid REFERENCES app_user (id),
    device_id     uuid REFERENCES device_token (id),
    content       jsonb       NOT NULL,
    content_hash  char(64)    NOT NULL,
    needs_review  boolean     NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (origin <> 'MACHINE' OR run_id IS NOT NULL),
    CHECK (origin <> 'USER' OR user_id IS NOT NULL)
);
CREATE INDEX recipe_revision_recipe_idx ON recipe_revision (recipe_id, created_at);
CREATE TRIGGER recipe_revision_immutable BEFORE UPDATE OR DELETE ON recipe_revision
    FOR EACH ROW EXECUTE FUNCTION rd_reject_change();

ALTER TABLE recipe ADD CONSTRAINT recipe_head_fk FOREIGN KEY (head_revision_id) REFERENCES recipe_revision (id);

-- Identity keys: one web page = one recipe. Raw value and normalizer version allow recomputing later.
CREATE TABLE recipe_key (
    kind               text        NOT NULL CHECK (kind IN ('CANONICAL_URL', 'JSONLD_ID')),
    value              text        NOT NULL,
    raw_value          text        NOT NULL,
    normalizer_version int         NOT NULL,
    recipe_id          uuid        NOT NULL REFERENCES recipe (id),
    capture_id         uuid REFERENCES capture (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, value)
);
CREATE INDEX recipe_key_recipe_idx ON recipe_key (recipe_id);

-- The user's decision for one candidate of a capture. May arrive before the candidate exists.
CREATE TABLE capture_resolution (
    capture_id          uuid        NOT NULL REFERENCES capture (id),
    idx                 int         NOT NULL,
    action              text        NOT NULL CHECK (action IN ('NEW', 'UPDATE')),
    target_recipe_id    uuid REFERENCES recipe (id),
    user_id             uuid        NOT NULL REFERENCES app_user (id),
    device_id           uuid        NOT NULL REFERENCES device_token (id),
    requested_at        timestamptz NOT NULL DEFAULT now(),
    applied_at          timestamptz,
    applied_run_id      uuid REFERENCES processing_run (id),
    applied_recipe_id   uuid REFERENCES recipe (id),
    applied_revision_id uuid REFERENCES recipe_revision (id),
    message             text,
    PRIMARY KEY (capture_id, idx),
    CHECK ((action = 'UPDATE') = (target_recipe_id IS NOT NULL))
);

-- Machine-readable reason next to the human-readable message (e.g. AWAITING_EXTRACTION, PROCESSING_FAILED).
ALTER TABLE capture_state ADD COLUMN reason text;
