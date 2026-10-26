-- Version of the recipe DTO format (the capture contract's schemaVersion) a candidate or revision content follows,
-- so contents of different contract versions can be told apart once a new version exists. Existing rows are v1.
ALTER TABLE recipe_candidate ADD COLUMN schema_version int NOT NULL DEFAULT 1;
ALTER TABLE recipe_candidate ALTER COLUMN schema_version DROP DEFAULT;

ALTER TABLE recipe_revision ADD COLUMN schema_version int NOT NULL DEFAULT 1;
ALTER TABLE recipe_revision ALTER COLUMN schema_version DROP DEFAULT;
