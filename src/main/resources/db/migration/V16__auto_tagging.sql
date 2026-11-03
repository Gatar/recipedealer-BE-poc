-- Tags chosen by Claude Haiku from the catalog for a new recipe the user did not tag (one row per recipe, once).
-- pending: the capture waits in 'processing' until the tagging job finished it. skipped: no call made or its tags not
-- assigned (recipe gone, tagged by the user in the meantime, catalog emptied). tags: what the model chose.
CREATE TABLE auto_tagging (
    id                          uuid PRIMARY KEY,
    recipe_id                   uuid        NOT NULL UNIQUE REFERENCES recipe (id),
    capture_id                  uuid        NOT NULL REFERENCES capture (id),
    status                      text        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'succeeded', 'skipped', 'failed')),
    tags                        text[],
    prompt_version              text,
    error_type                  text,
    error                       text,
    model                       text,
    stop_reason                 text,
    input_tokens                bigint,
    cache_creation_input_tokens bigint,
    cache_read_input_tokens     bigint,
    output_tokens               bigint,
    latency_ms                  bigint,
    cost_usd                    numeric(12, 6),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    finished_at                 timestamptz
);
CREATE INDEX auto_tagging_pending_idx ON auto_tagging (capture_id) WHERE status = 'pending';
