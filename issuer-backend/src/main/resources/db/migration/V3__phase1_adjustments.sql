-- Phase 1 schema touch-ups.
--   1. cnf_key_jwk is only populated once a real holder wallet is wired (Phase 2).
--      Until then the dev issuance endpoint issues device-unbound credentials,
--      so the column must allow NULL.
--   2. Seed a demo student so `POST /dev/credential/{studentId}` has a target.
--      Not a privacy concern: synthetic record, not a real person.

ALTER TABLE credential ALTER COLUMN cnf_key_jwk DROP NOT NULL;

INSERT INTO students (id, student_id, university_id, given_name, family_name, date_of_birth)
VALUES (
    '00000000-0000-0000-0000-000000000010',
    '0036123456',
    'fer.unizg.hr',
    'Ana',
    'Anić',
    '2004-05-12'
);
