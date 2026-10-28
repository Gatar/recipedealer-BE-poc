-- Captures stay immutable, but a capture that never became a recipe can be deleted (DELETE /v1/captures/{id}, e.g. an
-- invoice shared by mistake). Deleting is allowed only inside a transaction that set rd.delete_capture = 'on'.
CREATE FUNCTION rd_reject_delete_unless_allowed() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF coalesce(current_setting('rd.delete_capture', true), '') = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$;

DROP TRIGGER capture_immutable ON capture;
CREATE TRIGGER capture_immutable BEFORE UPDATE ON capture
    FOR EACH ROW EXECUTE FUNCTION rd_reject_change();
CREATE TRIGGER capture_delete_guard BEFORE DELETE ON capture
    FOR EACH ROW EXECUTE FUNCTION rd_reject_delete_unless_allowed();

DROP TRIGGER capture_attachment_immutable ON capture_attachment;
CREATE TRIGGER capture_attachment_immutable BEFORE UPDATE ON capture_attachment
    FOR EACH ROW EXECUTE FUNCTION rd_reject_change();
CREATE TRIGGER capture_attachment_delete_guard BEFORE DELETE ON capture_attachment
    FOR EACH ROW EXECUTE FUNCTION rd_reject_delete_unless_allowed();
