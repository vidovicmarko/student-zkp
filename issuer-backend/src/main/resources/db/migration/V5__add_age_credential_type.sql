-- Add a second credential type to demonstrate format-agnosticism (Phase 3).
-- Age/v1 credential proves only age threshold without revealing identity.
-- Issued to any adult using the same student database as source.

INSERT INTO credential_type (uri, display_name, schema_json, disclosure_policy, default_validity_days, issuer_id)
VALUES (
    'https://studentzk.eu/types/age/v1',
    'Age Verification (18+)',
    '{
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "required": ["age_equal_or_over", "valid_until"],
      "properties": {
        "age_equal_or_over": {"type": "object", "additionalProperties": {"type": "boolean"}},
        "valid_until":       {"type": "string", "format": "date"}
      }
    }'::jsonb,
    '{
      "always_hidden":           [],
      "selectively_disclosable": ["age_equal_or_over.18"],
      "always_disclosed":        ["valid_until"]
    }'::jsonb,
    365,
    '00000000-0000-0000-0000-000000000001'
);
