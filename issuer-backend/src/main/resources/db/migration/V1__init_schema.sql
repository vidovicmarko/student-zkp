-- StudentZK generic credential-type-agnostic schema (final_plan §5.9).
-- Design principle: nothing in the data model is student-specific.
-- `students` is the issuer's authentic source (raw PII stays here, never in a credential);
-- `credential_type` is the registry of issuable credential schemas;
-- `credential` is the issued verifiable credential;
-- `integrity_assertions` is the Play Integrity audit log (§5.8, Layer 2).

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Authoritative issuer record. Seeded by V2.
CREATE TABLE issuer (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uri          TEXT         NOT NULL UNIQUE,
    display_name TEXT         NOT NULL,
    jwks_uri     TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Authoritative source-of-truth for enrolled persons (universities: student registry).
-- Raw PII lives here and ONLY here. Credentials carry hashes + derived predicates.
CREATE TABLE students (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id    VARCHAR(50)  NOT NULL UNIQUE,
    university_id VARCHAR(100) NOT NULL,
    given_name    VARCHAR(200) NOT NULL,
    family_name   VARCHAR(200) NOT NULL,
    date_of_birth DATE         NOT NULL,
    photo_url     TEXT,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Registry of credential types. Admin can register new types by uploading a
-- JSON-Schema + display template + selective-disclosure policy. The same
-- issuance/verification pipeline works for every registered type.
CREATE TABLE credential_type (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uri                   TEXT        NOT NULL UNIQUE,
    display_name          TEXT        NOT NULL,
    schema_json           JSONB       NOT NULL,
    disclosure_policy     JSONB       NOT NULL,
    default_validity_days INTEGER     NOT NULL DEFAULT 365,
    issuer_id             UUID        NOT NULL REFERENCES issuer(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Issued verifiable credential. Generic over credential_type.
-- `subject_did` is derived from the holder's hardware-bound public key.
-- `attributes` conforms to credential_type.schema_json.
-- `cnf_key_jwk` is the holder's StrongBox/TEE public key (OID4VCI `cnf` claim).
-- `status_idx` is the bit position in the IETF Token Status List.
CREATE TABLE credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type_id         UUID         NOT NULL REFERENCES credential_type(id),
    subject_did     TEXT         NOT NULL,
    attributes      JSONB        NOT NULL,
    photo_hash      BYTEA,
    cnf_key_jwk     JSONB        NOT NULL,
    status_idx      INTEGER      NOT NULL,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ  NOT NULL,
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_credential_type        ON credential(type_id);
CREATE INDEX idx_credential_subject     ON credential(subject_did);
CREATE INDEX idx_credential_status_idx  ON credential(status_idx);

-- Play Integrity audit trail (final_plan §5.8, Layer 2).
-- Every integrity verdict (at issuance and at presentation) is logged with
-- the request_hash it was bound to, to prevent token reuse.
CREATE TABLE integrity_assertions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_did  TEXT         NOT NULL,
    nonce        BYTEA        NOT NULL UNIQUE,
    request_hash BYTEA        NOT NULL,
    verdict_json JSONB        NOT NULL,
    verdict_ok   BOOLEAN      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_integrity_subject ON integrity_assertions(subject_did);
