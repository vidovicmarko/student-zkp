-- Replace the racy `credentialRepo.count()` allocation in StudentIssuanceService
-- with a Postgres sequence. Two concurrent issuances would otherwise collide on
-- the same bit in the IETF Token Status List (final_plan §9 risk register).

CREATE SEQUENCE IF NOT EXISTS credential_status_idx_seq
    AS INTEGER
    MINVALUE 0
    START WITH 0
    INCREMENT BY 1;

-- If the credential table already has rows (re-applying onto an in-progress dev
-- DB), advance the sequence past the highest used bit so the next nextval() is
-- collision-free. No-op on a fresh schema.
DO $$
DECLARE
    max_idx INTEGER;
BEGIN
    SELECT MAX(status_idx) INTO max_idx FROM credential;
    IF max_idx IS NOT NULL THEN
        PERFORM setval('credential_status_idx_seq', max_idx, TRUE);
    END IF;
END $$;
