-- A user's e-mail address: required for owners (checked by the application: existing owners set it later), optional
-- for members, unique among live users (case ignored). Not verified; meant for password resets by name or e-mail.
ALTER TABLE app_user ADD COLUMN email text CHECK (length(email) <= 254);
CREATE UNIQUE INDEX app_user_email_key ON app_user (lower(email)) WHERE deleted_at IS NULL AND email IS NOT NULL;

-- Single-use codes an owner generates for an existing account: the code logs another device into that account.
-- Only the SHA-256 of the code is stored.
CREATE TABLE login_code (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    code_hash  bytea       NOT NULL UNIQUE,
    created_by uuid        NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz
);
CREATE INDEX login_code_user_idx ON login_code (user_id);
