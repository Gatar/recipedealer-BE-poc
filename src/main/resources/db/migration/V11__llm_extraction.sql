-- LLM extraction: every capture goes through a cascade of steps (heuristic → Claude Haiku → Claude Sonnet). Each step
-- is its own run; a run that fails validation is 'rejected' (candidates kept for inspection, not applied).
ALTER TABLE processing_run DROP CONSTRAINT processing_run_status_check;
ALTER TABLE processing_run ADD CONSTRAINT processing_run_status_check
    CHECK (status IN ('running', 'succeeded', 'awaiting_extraction', 'rejected', 'failed'));

-- purpose: pipeline (normal processing), forced (a user asked for LLM extraction), comparison (admin, never applied).
-- Usage and cost of LLM steps; cost is computed with the prices configured when the run finished.
ALTER TABLE processing_run
    ADD COLUMN purpose                     text    NOT NULL DEFAULT 'pipeline'
        CHECK (purpose IN ('pipeline', 'forced', 'comparison')),
    ADD COLUMN step                        text    NOT NULL DEFAULT 'heuristic',
    ADD COLUMN cascade_id                  uuid,
    ADD COLUMN validation_errors           text[],
    ADD COLUMN error_type                  text,
    ADD COLUMN model                       text,
    ADD COLUMN stop_reason                 text,
    ADD COLUMN input_tokens                bigint,
    ADD COLUMN cache_creation_input_tokens bigint,
    ADD COLUMN cache_read_input_tokens     bigint,
    ADD COLUMN output_tokens               bigint,
    ADD COLUMN latency_ms                  bigint,
    ADD COLUMN cost_usd                    numeric(12, 6);
CREATE INDEX processing_run_cascade_idx ON processing_run (cascade_id);

-- One job runs one cascade step; the next step is enqueued by the transaction that finishes the previous one.
ALTER TABLE job
    ADD COLUMN step       text NOT NULL DEFAULT 'heuristic',
    ADD COLUMN purpose    text NOT NULL DEFAULT 'pipeline'
        CHECK (purpose IN ('pipeline', 'forced', 'comparison')),
    ADD COLUMN cascade_id uuid;

-- Dead man's switch for the API key: one row per check (the URL is pinged only after a successful check).
CREATE TABLE api_key_check (
    id            uuid PRIMARY KEY,
    checked_at    timestamptz NOT NULL DEFAULT now(),
    success       boolean     NOT NULL,
    model         text,
    error_type    text,
    http_status   int,
    input_tokens  bigint,
    output_tokens bigint,
    cost_usd      numeric(12, 6),
    latency_ms    bigint,
    pinged        boolean     NOT NULL
);
