-- The language a new recipe from this capture is translated into (header Translate-To of the upload, ISO 639 code),
-- null = no translation. Set once on insert (captures stay immutable).
ALTER TABLE capture ADD COLUMN translate_to text CHECK (translate_to ~ '^[a-z]{2,3}$');

-- Translation by an LLM of a machine revision a capture with translate_to made the head of a recipe (new recipe,
-- UPDATE, re-read), at most one pending per recipe. pending: the capture waits in 'processing'. skipped: no call made
-- or its result not applied (edited by the user meanwhile, recipe gone). revision_id: the machine revision holding the
-- translation (the original stays in the history). tag_after: a new recipe, tagged once translated.
CREATE TABLE auto_translation (
    id                          uuid PRIMARY KEY,
    recipe_id                   uuid        NOT NULL REFERENCES recipe (id),
    capture_id                  uuid        NOT NULL REFERENCES capture (id),
    target_language             text        NOT NULL,
    tag_after                   boolean     NOT NULL,
    source_language             text,
    status                      text        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'succeeded', 'skipped', 'failed')),
    revision_id                 uuid REFERENCES recipe_revision (id),
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
CREATE INDEX auto_translation_pending_idx ON auto_translation (capture_id) WHERE status = 'pending';
CREATE UNIQUE INDEX auto_translation_one_pending_idx ON auto_translation (recipe_id) WHERE status = 'pending';
CREATE INDEX auto_translation_recipe_idx ON auto_translation (recipe_id);
