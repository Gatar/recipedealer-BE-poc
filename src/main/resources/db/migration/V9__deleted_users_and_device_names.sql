-- Deleted users (DELETE /v1/admin/users/{userId}) stay for the history that references them; their name is free again.
ALTER TABLE app_user ADD COLUMN deleted_at timestamptz;
DROP INDEX app_user_name_key;
CREATE UNIQUE INDEX app_user_name_key ON app_user (lower(name)) WHERE deleted_at IS NULL;

-- One device per user and device name: logging in again on the same device replaces its token.
CREATE UNIQUE INDEX device_token_user_name_key ON device_token (user_id, lower(device_name));
