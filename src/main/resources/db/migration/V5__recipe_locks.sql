-- Edit lease per recipe. The lock belongs to an editing session (lock_id); the user is stored for display only.
CREATE TABLE recipe_lock (
    recipe_id   uuid PRIMARY KEY REFERENCES recipe (id),
    lock_id     uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES app_user (id),
    device_id   uuid        NOT NULL REFERENCES device_token (id),
    acquired_at timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL
);
