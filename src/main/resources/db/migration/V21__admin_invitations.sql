-- Invitations the admin creates (dashboard) have no inviting user.
ALTER TABLE household_invitation ALTER COLUMN created_by DROP NOT NULL;
