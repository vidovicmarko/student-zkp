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

CREATE TABLE issued_credentials (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id      UUID         NOT NULL REFERENCES students(id),
    credential_type VARCHAR(50)  NOT NULL,
    status_index    INTEGER      NOT NULL,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_issued_credentials_student ON issued_credentials(student_id);
CREATE INDEX idx_issued_credentials_status  ON issued_credentials(status_index);
