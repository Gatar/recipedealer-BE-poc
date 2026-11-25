-- Households: every user belongs to exactly one; captures, recipes, identity keys, tags and uploaded photos belong to
-- one and are visible only to its devices. Everything that existed before goes to the default household.
CREATE TABLE household (
    id         uuid PRIMARY KEY,
    name       text        NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 100),
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
-- Fixed id, referenced by the code (Households.DEFAULT_ID) for admin enrollment and the registration code.
INSERT INTO household (id, name) VALUES ('00000000-0000-7000-8000-000000000001', 'Default household');

-- Users: user names stay unique across all households (login is by user name alone).
ALTER TABLE app_user ADD COLUMN household_id uuid REFERENCES household (id);
ALTER TABLE app_user ADD COLUMN role text NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member'));
UPDATE app_user SET household_id = '00000000-0000-7000-8000-000000000001', role = 'owner';
ALTER TABLE app_user ALTER COLUMN household_id SET NOT NULL;
CREATE INDEX app_user_household_idx ON app_user (household_id);

-- Captures (immutable rows: the trigger is off only while the existing rows get their household)
ALTER TABLE capture ADD COLUMN household_id uuid REFERENCES household (id);
ALTER TABLE capture DISABLE TRIGGER capture_immutable;
UPDATE capture SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE capture ENABLE TRIGGER capture_immutable;
ALTER TABLE capture ALTER COLUMN household_id SET NOT NULL;
CREATE INDEX capture_household_idx ON capture (household_id, received_at);
CREATE INDEX capture_body_idx ON capture (body_sha256);
CREATE INDEX capture_attachment_blob_idx ON capture_attachment (blob_sha256);

-- Recipes; (id, household_id) is unique so that rows of other tables can require the recipe's own household.
ALTER TABLE recipe ADD COLUMN household_id uuid REFERENCES household (id);
UPDATE recipe SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE recipe ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE recipe ADD CONSTRAINT recipe_id_household_key UNIQUE (id, household_id);
DROP INDEX recipe_sync_idx;
CREATE INDEX recipe_sync_idx ON recipe (household_id, updated_at, id);
CREATE INDEX recipe_thumbnail_idx ON recipe (thumbnail_blob);

-- Identity keys: one web page = one recipe per household.
ALTER TABLE recipe_key ADD COLUMN household_id uuid;
UPDATE recipe_key SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE recipe_key ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE recipe_key DROP CONSTRAINT recipe_key_pkey;
ALTER TABLE recipe_key ADD PRIMARY KEY (household_id, kind, value);
ALTER TABLE recipe_key ADD CONSTRAINT recipe_key_recipe_household_fkey
    FOREIGN KEY (recipe_id, household_id) REFERENCES recipe (id, household_id);

-- Tags: one catalog per household. Category and tag names are unique within the household.
ALTER TABLE recipe_tag DROP CONSTRAINT recipe_tag_category_tag_fkey;
ALTER TABLE tag DROP CONSTRAINT tag_category_fkey;
ALTER TABLE tag DROP CONSTRAINT tag_pkey;
ALTER TABLE tag_category DROP CONSTRAINT tag_category_pkey;

ALTER TABLE tag_category ADD COLUMN household_id uuid REFERENCES household (id);
UPDATE tag_category SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE tag_category ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE tag_category ADD PRIMARY KEY (household_id, name);

ALTER TABLE tag ADD COLUMN household_id uuid;
UPDATE tag SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE tag ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE tag ADD PRIMARY KEY (household_id, category, name);
ALTER TABLE tag ADD CONSTRAINT tag_category_fkey FOREIGN KEY (household_id, category)
    REFERENCES tag_category (household_id, name) ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE recipe_tag ADD COLUMN household_id uuid;
UPDATE recipe_tag SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE recipe_tag ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE recipe_tag ADD CONSTRAINT recipe_tag_tag_fkey FOREIGN KEY (household_id, category, tag)
    REFERENCES tag (household_id, category, name) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE recipe_tag ADD CONSTRAINT recipe_tag_recipe_household_fkey
    FOREIGN KEY (recipe_id, household_id) REFERENCES recipe (id, household_id);
DROP INDEX recipe_tag_tag_idx;
CREATE INDEX recipe_tag_tag_idx ON recipe_tag (household_id, category, tag);

-- Uploaded photos: the same picture uploaded in two households is one blob with a row per household.
ALTER TABLE user_photo ADD COLUMN household_id uuid REFERENCES household (id);
UPDATE user_photo SET household_id = '00000000-0000-7000-8000-000000000001';
ALTER TABLE user_photo ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE user_photo DROP CONSTRAINT user_photo_pkey;
ALTER TABLE user_photo ADD PRIMARY KEY (household_id, blob_sha256);
CREATE INDEX user_photo_blob_idx ON user_photo (blob_sha256);
