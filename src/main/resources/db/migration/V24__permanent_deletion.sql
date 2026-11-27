-- Permanent deletion (user, 2026-11-27): deleted accounts, recipes and households keep nothing.

-- A deleted account keeps no personal data; the row (and its devices' rows) stays only because immutable captures and
-- the history point to it. Names are gone, tokens replaced by random values.
ALTER TABLE app_user ALTER COLUMN name DROP NOT NULL;
ALTER TABLE device_token ALTER COLUMN device_name DROP NOT NULL;
UPDATE app_user SET name = NULL, email = NULL, password_hash = NULL WHERE deleted_at IS NOT NULL;
UPDATE device_token d
SET device_name = NULL, revoked_at = coalesce(d.revoked_at, now()),
    token_hash  = sha256(convert_to(gen_random_uuid()::text, 'UTF8'))
FROM app_user u
WHERE u.id = d.user_id AND u.deleted_at IS NOT NULL;
DELETE FROM login_code c USING app_user u WHERE u.id = c.user_id AND u.deleted_at IS NOT NULL;

-- A deleted recipe is purged in the background (PurgeService): its revisions, keys, tags and the captures only it
-- used go; the row stays as the sync tombstone (id, household, times) with purged_at set.
ALTER TABLE recipe ADD COLUMN purged_at timestamptz;

-- Revisions stay immutable but can be deleted with their recipe (same switch as for captures, V15).
DROP TRIGGER recipe_revision_immutable ON recipe_revision;
CREATE TRIGGER recipe_revision_immutable BEFORE UPDATE ON recipe_revision
    FOR EACH ROW EXECUTE FUNCTION rd_reject_change();
CREATE TRIGGER recipe_revision_delete_guard BEFORE DELETE ON recipe_revision
    FOR EACH ROW EXECUTE FUNCTION rd_reject_delete_unless_allowed();

-- Stored photos are WebP since V24; older ones are JPEG (served with this type, converted by PhotoService).
ALTER TABLE user_photo ADD COLUMN media_type text NOT NULL DEFAULT 'image/jpeg';
ALTER TABLE dish_photo ADD COLUMN media_type text;
UPDATE dish_photo SET media_type = 'image/jpeg' WHERE blob_sha256 IS NOT NULL;
ALTER TABLE pdf_image ADD COLUMN media_type text;
UPDATE pdf_image SET media_type = 'image/jpeg' WHERE blob_sha256 IS NOT NULL;
