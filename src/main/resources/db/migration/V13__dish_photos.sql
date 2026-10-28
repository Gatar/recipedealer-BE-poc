-- What an LLM run found about the dish photo among a capture's photos and screenshots; one row per run that saw
-- images. No blob means none of them shows the dish. The blob is the photo cut out of the attachment (JPEG),
-- x / y / width / height its box in the original attachment's pixels.
CREATE TABLE dish_photo (
    run_id          uuid PRIMARY KEY REFERENCES processing_run (id),
    capture_id      uuid NOT NULL REFERENCES capture (id),
    attachment_name text,
    blob_sha256     char(64) REFERENCES blob (sha256),
    x               int,
    y               int,
    width           int,
    height          int,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((blob_sha256 IS NULL) = (attachment_name IS NULL))
);
CREATE INDEX dish_photo_blob_idx ON dish_photo (blob_sha256);
