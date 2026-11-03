-- Photos a user uploaded for a recipe (POST /v1/blobs), stored as JPEG of at most 1600 px on the longer edge whatever
-- was sent. A recipe shows one through images[0] = 'blob:<sha256>'; it then is the recipe's thumbnail.
CREATE TABLE user_photo (
    blob_sha256 char(64)    PRIMARY KEY REFERENCES blob (sha256),
    width       int         NOT NULL,
    height      int         NOT NULL,
    device_id   uuid        NOT NULL REFERENCES device_token (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);
