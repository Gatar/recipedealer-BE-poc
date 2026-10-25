-- Two-level tags "category:tag" (e.g. meal:breakfast). The name is the stable, readable id; the title is what the
-- app shows (e.g. "Posiłek" / "Śniadanie"). Renaming cascades to recipe_tag; positions are dense per level.
CREATE TABLE tag_category (
    name     text PRIMARY KEY CHECK (name ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(name) <= 40),
    title    text NOT NULL CHECK (btrim(title) <> '' AND length(title) <= 60),
    position int  NOT NULL
);

CREATE TABLE tag (
    category text NOT NULL REFERENCES tag_category (name) ON UPDATE CASCADE ON DELETE CASCADE,
    name     text NOT NULL CHECK (name ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(name) <= 40),
    title    text NOT NULL CHECK (btrim(title) <> '' AND length(title) <= 60),
    position int  NOT NULL,
    PRIMARY KEY (category, name)
);

-- Assignments are not part of the recipe content: no revision, no edit lock. Changes bump recipe.updated_at.
CREATE TABLE recipe_tag (
    recipe_id uuid NOT NULL REFERENCES recipe (id),
    category  text NOT NULL,
    tag       text NOT NULL,
    PRIMARY KEY (recipe_id, category, tag),
    FOREIGN KEY (category, tag) REFERENCES tag (category, name) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX recipe_tag_tag_idx ON recipe_tag (category, tag);

-- Tags chosen together with a NEW decision ("category:tag" ids), assigned when the recipe is created.
ALTER TABLE capture_resolution ADD COLUMN tags text[] NOT NULL DEFAULT '{}';
