-- Live recipes a household may have; NULL = no limit (the default household). New households get the configured
-- default (recipedealer.households.default-recipe-limit); the admin changes it per household.
ALTER TABLE household ADD COLUMN recipe_limit int CHECK (recipe_limit >= 0);
-- Claude API spending a household may cause per calendar month (UTC); NULL = no limit (the default household). New
-- households get recipedealer.households.default-llm-budget-usd.
ALTER TABLE household ADD COLUMN llm_budget_usd numeric(10, 2) CHECK (llm_budget_usd >= 0);
CREATE INDEX processing_run_started_idx ON processing_run (started_at);

-- Single-use invitation codes an owner hands out; only the SHA-256 of the code is stored.
CREATE TABLE household_invitation (
    id           uuid PRIMARY KEY,
    household_id uuid        NOT NULL REFERENCES household (id),
    code_hash    bytea       NOT NULL UNIQUE,
    created_by   uuid        NOT NULL REFERENCES app_user (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    used_at      timestamptz,
    used_by      uuid REFERENCES app_user (id),
    revoked_at   timestamptz
);
CREATE INDEX household_invitation_household_idx ON household_invitation (household_id);
