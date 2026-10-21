CREATE TABLE app_user (
    id         uuid PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_name_key ON app_user (lower(name));

-- One row per enrolled device. Only the SHA-256 of the bearer token is stored.
CREATE TABLE device_token (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES app_user (id),
    device_name  text        NOT NULL,
    token_hash   bytea       NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);
CREATE INDEX device_token_user_idx ON device_token (user_id);
