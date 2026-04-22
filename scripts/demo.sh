#!/usr/bin/env bash
# End-to-end Phase 1 demo: ask the issuer for Ana Anić's Student credential,
# print the SD-JWT-VC and its human-readable disclosures, then point you at
# the verifier. Requires a running issuer (localhost:8080) and a running
# verifier (localhost:5173). Needs `curl` and `jq`.
set -euo pipefail

ISSUER="${ISSUER:-http://localhost:8080}"
VERIFIER="${VERIFIER:-http://localhost:5173}"
STUDENT_ID="${STUDENT_ID:-0036123456}"

for bin in curl jq; do
  command -v "$bin" >/dev/null 2>&1 || { echo "demo.sh needs '$bin' on PATH" >&2; exit 1; }
done

echo "→ POST $ISSUER/dev/credential/$STUDENT_ID"
response="$(curl --silent --show-error --fail -X POST \
  -H 'Content-Type: application/json' -d '{}' \
  "$ISSUER/dev/credential/$STUDENT_ID")"

credential_id="$(jq -r '.credentialId' <<<"$response")"
status_idx="$(jq -r '.statusIdx'     <<<"$response")"
sd_jwt="$(jq -r '.sdJwt'              <<<"$response")"

echo
echo "Credential ID : $credential_id"
echo "Status index  : $status_idx"
echo
echo "── SD-JWT-VC (compact) ────────────────────────────────────────────"
echo "$sd_jwt"
echo "───────────────────────────────────────────────────────────────────"
echo

# base64url → base64 → stdout. tr handles the URL-safe alphabet; awk pads to
# a multiple of 4 so `base64 -d` accepts it. Works on GNU coreutils and BSD.
b64url_decode() {
  local s="$1"
  s="$(printf '%s' "$s" | tr '_-' '/+')"
  local pad=$(( (4 - ${#s} % 4) % 4 ))
  printf '%s' "$s"; printf '=%.0s' $(seq 1 $pad)
}

echo "Disclosures (each is base64url(JSON [salt, name, value])):"
jq -r '.disclosures[] | .disclosureB64' <<<"$response" | while IFS= read -r d; do
  decoded_json="$(b64url_decode "$d" | base64 -d 2>/dev/null || true)"
  pretty="$(jq -c '.' <<<"$decoded_json" 2>/dev/null || echo "$decoded_json")"
  echo "  • $pretty"
done

echo
echo "Next step:"
echo "  1. Open $VERIFIER in a browser."
echo "  2. Paste the SD-JWT-VC above into the textarea."
echo "  3. Click Verify. You should see is_student=true and age_equal_or_over.18=true,"
echo "     while given_name_hash / family_name_hash stay hidden unless you pass them."
