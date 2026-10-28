-- How far (degrees clockwise) the cut-out was turned to be upright; the box stays in the original's orientation.
ALTER TABLE dish_photo ADD COLUMN rotation int NOT NULL DEFAULT 0 CHECK (rotation IN (0, 90, 180, 270));
