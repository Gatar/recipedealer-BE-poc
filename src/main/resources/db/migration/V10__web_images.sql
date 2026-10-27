-- Server copies of recipe photos from other sites (a head's images[0]), one row per URL. A row without a blob is a
-- download that failed: retried at next_attempt_at, given up when that is null.
CREATE TABLE web_image (
    url             text PRIMARY KEY,
    blob_sha256     char(64) REFERENCES blob (sha256),
    media_type      text,
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    error           text,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((blob_sha256 IS NULL) = (media_type IS NULL))
);
CREATE INDEX web_image_blob_idx ON web_image (blob_sha256);
