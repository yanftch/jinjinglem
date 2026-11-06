CREATE FUNCTION reject_member_admin_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'member_admin_audit is append-only';
END;
$$;

CREATE TRIGGER member_admin_audit_append_only
BEFORE UPDATE OR DELETE ON member_admin_audit
FOR EACH ROW EXECUTE FUNCTION reject_member_admin_audit_mutation();
