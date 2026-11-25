-- Live recipes a household may have; NULL = no limit (the default household). New households get the configured
-- default (recipedealer.households.default-recipe-limit); the admin changes it per household.
ALTER TABLE household ADD COLUMN recipe_limit int CHECK (recipe_limit >= 0);

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
