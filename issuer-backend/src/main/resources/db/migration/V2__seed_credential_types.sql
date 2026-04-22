-- Seed the FER Zagreb issuer + the Student credential type (final_plan Phase 0).
-- Additional types (age/v1, library/v1, transit/v1, event/v1) are Phase 3 work
-- and can be added via the admin API or a later migration.

INSERT INTO issuer (id, uri, display_name, jwks_uri)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'https://issuer.studentzkp.hr',
    'FER Zagreb',
    'https://issuer.studentzkp.hr/.well-known/jwks.json'
);

INSERT INTO credential_type (uri, display_name, schema_json, disclosure_policy, default_validity_days, issuer_id)
VALUES (
    'https://studentzk.eu/types/student/v1',
    'University Student',
    '{
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["student_id", "university_id", "given_name_hash", "family_name_hash", "is_student", "age_equal_or_over", "valid_until"],
      "properties": {
        "student_id":        {"type": "string"},
        "university_id":     {"type": "string"},
        "given_name_hash":   {"type": "string"},
        "family_name_hash":  {"type": "string"},
        "photo_hash":        {"type": "string"},
        "is_student":        {"type": "boolean"},
        "age_equal_or_over": {"type": "object", "additionalProperties": {"type": "boolean"}},
        "valid_until":       {"type": "string", "format": "date"}
      }
    }'::jsonb,
    '{
      "always_hidden":           ["student_id", "given_name_hash", "family_name_hash"],
      "selectively_disclosable": ["university_id", "photo_hash", "is_student", "age_equal_or_over.18"],
      "always_disclosed":        ["valid_until"]
    }'::jsonb,
    365,
    '00000000-0000-0000-0000-000000000001'
);
