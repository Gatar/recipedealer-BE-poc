-- Self-service accounts: bcrypt hash of the user's password. NULL for users enrolled by the admin until they set one
-- (PUT /v1/me/password with a device token); such users cannot log in with a password.
ALTER TABLE app_user ADD COLUMN password_hash text;
