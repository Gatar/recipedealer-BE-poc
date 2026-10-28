-- The photo found in a PDF attachment: the largest image of the first page that has one, re-encoded as JPEG.
-- One row per PDF attachment once it has been searched; a row without a blob means no photo (error: why it failed).
CREATE TABLE pdf_image (
    capture_id      uuid        NOT NULL,
    attachment_name text        NOT NULL,
    blob_sha256     char(64) REFERENCES blob (sha256),
    page            int,
    width           int,
    height          int,
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (capture_id, attachment_name),
    FOREIGN KEY (capture_id, attachment_name) REFERENCES capture_attachment (capture_id, name),
    CHECK ((blob_sha256 IS NULL) = (page IS NULL))
);
CREATE INDEX pdf_image_blob_idx ON pdf_image (blob_sha256);
