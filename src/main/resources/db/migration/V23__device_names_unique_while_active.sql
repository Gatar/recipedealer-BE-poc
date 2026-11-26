-- A device name is unique among the user's working devices only (user, 2026-11-26): revoking a device frees its
-- name; logging in with the name of a working device replaces that device's token.
DROP INDEX device_token_user_name_key;
CREATE UNIQUE INDEX device_token_user_name_key ON device_token (user_id, lower(device_name)) WHERE revoked_at IS NULL;

-- A login code made for one of the user's devices logs in as that device (its name) instead of adding a device.
ALTER TABLE login_code ADD COLUMN device_id uuid REFERENCES device_token (id);
