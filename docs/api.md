# StudentZK Issuer — HTTP API

Hand-written reference for the issuer-backend's HTTP surface. Mirrors what `springdoc-openapi` would generate at `/v3/api-docs` and `/swagger-ui.html` once the issuer is rebuilt with that dependency. Use this doc when the MCP / live spec isn't reachable.

**Base URL (dev):** `http://localhost:8080`
**Base URL (prod):** value of `studentzkp.issuer.publicBaseUrl` (defaults to localhost in dev, override via `ISSUER_PUBLIC_BASE_URL` env)

---

## Auth model

Three classes of endpoints. The class determines what's required.

| Class | Auth | Used by |
|---|---|---|
| **Public** | None | Wallets (OID4VCI) and verifiers (JWKS, status list) |
| **Authenticated** | HTTP Basic against `studentzkp.admin.username` + `studentzkp.admin.password` | Issuer-internal callers (Play Integrity gateway, future admin UI) |
| **Dev-only** | None, but only registered under the `dev-shortcut` Spring profile | `scripts/demo.sh`, manual testing |

The admin password defaults to a per-boot generated value — look for the line `studentzkp.admin.password not set — generated one for this boot: 'XXXX...'` in the issuer logs. Set `STUDENTZKP_ADMIN_PASSWORD` (env) or `studentzkp.admin.password` (config) for a stable credential.

CORS allows `http://localhost:5173` (the verifier-web SPA) on `GET`/`POST`/`OPTIONS`.

---

## Public endpoints

### `GET /health`

Liveness probe. No auth, no profile gating.

**Response 200**
```json
{ "status": "ok", "service": "student-zkp-issuer" }
```

---

### `GET /actuator/health`

Spring Actuator health endpoint. Standard format.

**Response 200**
```json
{ "status": "UP" }
```

---

### `GET /.well-known/jwks.json`

Issuer's public signing keys in JWKS format. Verifiers fetch this to validate SD-JWT-VC signatures.

**Response 200** — `application/json`
```json
{
  "keys": [
    {
      "kty": "EC",
      "crv": "P-256",
      "kid": "5b8a...uuid",
      "alg": "ES256",
      "use": "sig",
      "x": "base64url-x-coordinate",
      "y": "base64url-y-coordinate"
    }
  ]
}
```

The `kid` is stable across restarts (the key is persisted to `studentzkp.issuer.keyPath`, default `./.studentzkp/issuer-signing-key.jwk`). Cache this response with a long TTL — verifiers can run offline against the cached JWKS.

---

### `GET /statuslist/uni-2026.json`

IETF Token Status List ([draft-ietf-oauth-status-list](https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)) for revocation. Each issued credential gets a bit position in this list; bit `0` = valid, bit `1` = revoked.

**Response 200** — `application/json`
```json
{
  "status_list": {
    "bits": 1,
    "lst": "eNpjYGAA…"
  }
}
```

`lst` is a `deflate`-compressed bitstring of size `studentzkp.statusList.capacity` bits (default 131072), then base64url-encoded. The verifier decodes, decompresses, and checks bit `idx` where `idx` comes from the credential's `status.status_list.idx` claim.

Cacheable (CDN-friendly). The verifier is allowed to fail soft on a stale list and surface a "freshness unknown" badge — see `docs/threat-model.md`.

---

### `GET /credential-offer/{offerId}`

OID4VCI Credential Offer fetched by reference. Wallets reach this URL after parsing the `openid-credential-offer://?credential_offer_uri=...` deep-link returned by `POST /dev/credential-offer/{studentId}`.

**Path parameters**
| Name | Type | Required | Notes |
|---|---|---|---|
| `offerId` | string | yes | Opaque ID returned by the offer-creation step |

**Response 200**
```json
{
  "credential_issuer": "https://issuer.studentzkp.hr",
  "credential_configuration_ids": ["UniversityStudent"],
  "grants": {
    "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
      "pre-authorized_code": "<opaque base64url string>"
    }
  }
}
```

**Errors**
| Status | Reason |
|---|---|
| `404` | Offer not found, expired, or already consumed |

---

### `POST /token`

OAuth 2.0 ([RFC 6749 §3.2](https://www.rfc-editor.org/rfc/rfc6749#section-3.2)) token endpoint with the OID4VCI pre-authorized-code grant. Form-encoded request.

**Request** — `application/x-www-form-urlencoded`
| Field | Required | Value |
|---|---|---|
| `grant_type` | yes | Must be `urn:ietf:params:oauth:grant-type:pre-authorized_code` |
| `pre-authorized_code` | yes | The code from the `/credential-offer/{offerId}` response |

**Response 200**
```json
{
  "access_token": "<opaque base64url string>",
  "token_type": "Bearer",
  "expires_in": 600,
  "c_nonce": "<opaque base64url string>",
  "c_nonce_expires_in": 600
}
```

The `c_nonce` is the verifier-supplied nonce the holder must include in the proof JWT at `POST /credential`. TTLs default to 10 minutes (`studentzkp.oid4vci.tokenTtlSeconds`).

**Errors**
| Status | Reason |
|---|---|
| `400` | `unsupported_grant_type` (not the pre-auth grant); pre-auth code unknown / expired / already redeemed |

---

### `POST /credential`

Mint the SD-JWT-VC. The wallet authenticates with the access token from `/token` and proves possession of its `cnf` key by signing a proof JWT bound to the issued `c_nonce`.

**Request headers**
| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <access_token>` |
| `Content-Type` | yes | `application/json` |

**Request body**
```json
{
  "format": "vc+sd-jwt",
  "proof": {
    "proof_type": "jwt",
    "jwt": "eyJhbGciOiJFUzI1NiIsInR5cCI6Im9wZW5pZDR2Y2ktcHJvb2Yrand0Iiwiandr...."
  }
}
```

**Proof JWT structure** (must be a compact JWS):
- **Header:** `{ "alg": "ES256" | "RS256", "typ": "openid4vci-proof+jwt", "jwk": <holder public JWK> }`
- **Payload:** `{ "aud": "<studentzkp.issuer.id>", "iat": <unix-seconds>, "nonce": "<c_nonce from /token>" }`

The issuer validates: signature against `header.jwk`, `aud` equals the configured issuer URI, `nonce` equals the bound `c_nonce`, `iat` within ±5 minutes. The `jwk` is then bound into the credential as the `cnf` claim.

**Response 200**
```json
{
  "credential": "eyJhbGc...~WyJhYmM...~WyJzdHV...~",
  "credentialId": "550e8400-e29b-41d4-a716-446655440000"
}
```

`credential` is the compact SD-JWT-VC: `<issuer_jwt>~<disclosure_1>~<disclosure_2>~...~`. See `verifier-web/src/lib/verify.ts` for the disclosure decoding rules.

**Errors**
| Status | Reason |
|---|---|
| `400` | Format not `vc+sd-jwt`; proof_type not `jwt`; proof JWT malformed; wrong `typ`; missing `jwk`; signature invalid; wrong `aud`; nonce mismatch; `iat` skew > 5min; access token already used |
| `401` | Missing/invalid Bearer header; access token unknown or expired |

---

## Authenticated endpoints (HTTP Basic)

### `POST /integrity/nonce`

Issues a fresh server-side nonce that the holder embeds in its Play Integrity classic request. Phase 1 stub returns a random nonce; Phase 2 logs and indexes it for replay detection (see `final_plan §5.8`).

**Auth:** HTTP Basic.

**Request body**
```json
{ "subjectDid": "did:jwk:..." }
```

**Response 200**
```json
{
  "nonce": "base64url-bytes",
  "expiresIn": 300
}
```

---

### `POST /integrity/verify`

Validates a Play Integrity token bound to a request hash. Phase 1 uses `StubIntegrityService` (always returns `ok: true`). Phase 2 calls Google's `decodeIntegrityToken` and enforces `MEETS_DEVICE_INTEGRITY` + `MEETS_STRONG_INTEGRITY` + `PLAY_RECOGNIZED`.

**Auth:** HTTP Basic.

**Request body**
```json
{
  "token": "<Play Integrity token>",
  "requestHash": "base64url-sha256-of-bound-payload"
}
```

**Response 200**
```json
{
  "ok": true,
  "reasons": []
}
```

When `ok` is `false`, `reasons` lists the failed verdicts (e.g., `["device_integrity_failed", "play_protect_warned"]`).

---

## Dev-only endpoints (`dev-shortcut` profile)

These routes only exist when the `dev-shortcut` Spring profile is active. The `local` profile group auto-includes it; for the Docker / native-postgres path pass `--spring.profiles.active=dev-shortcut` explicitly. The `SecurityConfig` denies `/dev/**` at the firewall whenever the profile is off — even if the controllers were somehow registered, they wouldn't be reachable.

### `POST /dev/credential/{studentId}`

Issue an SD-JWT-VC for a known student, skipping the OID4VCI handshake. No real wallet should ever call this — it exists for `scripts/demo.sh`.

**Path parameters**
| Name | Type | Required | Notes |
|---|---|---|---|
| `studentId` | string | yes | Matches `students.student_id` in the DB; seeded value is `0036123456` |

**Request body** (optional)
```json
{
  "cnfJwk": {
    "kty": "EC", "crv": "P-256",
    "x": "...", "y": "..."
  }
}
```

If `cnfJwk` is provided, the credential carries it under the `cnf` claim (mimicking what `/credential` does after wallet binding). Omit the body to issue an unbound credential.

**Response 200**
```json
{
  "credentialId": "550e8400-e29b-41d4-a716-446655440000",
  "statusIdx": 7,
  "sdJwt": "eyJhbGc...~WyJhYmM...~",
  "disclosures": [
    {
      "name": "is_student",
      "value": true,
      "disclosureB64": "WyJzYWx0Iiwi..."
    }
  ]
}
```

**Errors**
| Status | Reason |
|---|---|
| `404` | Unknown student |

---

### `POST /dev/credential/{credentialId}/revoke`

Mark a credential as revoked. The next `GET /statuslist/uni-2026.json` fetch will have the corresponding bit set.

**Path parameters**
| Name | Type | Required | Notes |
|---|---|---|---|
| `credentialId` | UUID | yes | The `credentialId` from a prior `/dev/credential/{studentId}` response |

**Response 200**
```json
{
  "credentialId": "550e8400-e29b-41d4-a716-446655440000",
  "statusIdx": 7,
  "revoked": true
}
```

**Errors**
| Status | Reason |
|---|---|
| `400` | Path is not a valid UUID |
| `404` | No credential with that ID |

---

### `POST /dev/credential-offer/{studentId}`

Mint an OID4VCI credential offer for a student. In production an admin UI calls this; in dev it's the easiest way to drive the full handshake.

**Path parameters**
| Name | Type | Required | Notes |
|---|---|---|---|
| `studentId` | string | yes | Matches `students.student_id` |

**Response 200**
```json
{
  "offer_id": "<opaque base64url string>",
  "pre_authorized_code": "<opaque base64url string>",
  "credential_offer_uri": "http://localhost:8080/credential-offer/<offer_id>",
  "deep_link": "openid-credential-offer://?credential_offer_uri=http%3A%2F%2Flocalhost%3A8080%2Fcredential-offer%2F<offer_id>",
  "expires_in_seconds": 300
}
```

QR-encode `deep_link` in any wallet to trigger the flow. The wallet then drives `GET /credential-offer/{offer_id}` → `POST /token` → `POST /credential`.

**Errors**
| Status | Reason |
|---|---|
| `404` | Not actually returned today; the offer is created without checking whether the student exists. Phase 3 should add validation. |

---

## Object schemas

### Issued SD-JWT-VC payload (decoded JWS body)

The compact form is `<jws>~<disclosure_1>~...~`. The JWS body looks like:

```json
{
  "iss": "https://issuer.studentzkp.hr",
  "iat": 1745520000,
  "exp": 1777056000,
  "vct": "https://studentzk.eu/types/student/v1",
  "sub": "did:jwk:synthetic-...",
  "cnf": {
    "jwk": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
  },
  "_sd": [
    "<sha256-base64url-of-disclosure-1>",
    "<sha256-base64url-of-disclosure-2>",
    "..."
  ],
  "_sd_alg": "sha-256",
  "valid_until": "2027-04-25",
  "status": {
    "status_list": {
      "idx": 7,
      "uri": "https://issuer.studentzkp.hr/statuslist/uni-2026.json"
    }
  }
}
```

Each disclosure (`~`-separated trailer segment) is `base64url(JSON.stringify([salt, name, value]))`. The verifier hashes each disclosure bytes with SHA-256 and confirms membership in the `_sd` array — that's the selective-disclosure binding.

The Student credential type's selectively-disclosable attributes (final_plan §5.9):
| Name | Type | Disclosed by default? |
|---|---|---|
| `is_student` | boolean | yes |
| `age_equal_or_over` | object (`{ "18": boolean }`) | yes |
| `university_id` | string | wallet's choice |
| `student_id` | string | wallet's choice (typically hidden) |
| `given_name_hash` | hex string | hidden |
| `family_name_hash` | hex string | hidden |

Always-disclosed (no `_sd` entry, in the JWS body itself):
- `valid_until`
- `status`

---

## What's not yet exposed over HTTP

These show up in the design docs but don't have endpoints in Phase 1.5. Not bugs — explicit non-goals for now:

- BBS+ proof verification (lives in the verifier-web bundle, not the issuer)
- KB-JWT verification (Phase 2 — `cnf` is plumbed but not enforced)
- Admin UI for credential type registration (Phase 3)
- Photo upload / signed-URL generation for liveness check (Phase 3)

---

## Quick recipes

### Mint + verify in one paste
```bash
curl -sX POST http://localhost:8080/dev/credential/0036123456 | jq .sdJwt
```
Paste the output into `http://localhost:5173`.

### Drive the OID4VCI flow with curl (no wallet)
The proof JWT can't be built with curl alone, but everything up to `/credential` is straightforward:
```bash
OFFER=$(curl -sX POST http://localhost:8080/dev/credential-offer/0036123456)
PA=$(echo "$OFFER" | jq -r .pre_authorized_code)
curl -sX POST http://localhost:8080/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code' \
  --data-urlencode "pre-authorized_code=$PA" | jq .
```
Use any JOSE lib (Python `jose`, Node `jose`, Java Nimbus) to build the `openid4vci-proof+jwt` and POST it to `/credential` with the `Bearer` header.

### Revoke + observe in the status list
```bash
CRED_ID=$(curl -sX POST http://localhost:8080/dev/credential/0036123456 | jq -r .credentialId)
curl -sX POST "http://localhost:8080/dev/credential/$CRED_ID/revoke" | jq .
curl -s http://localhost:8080/statuslist/uni-2026.json | jq .
```

### Health check
```bash
curl -s http://localhost:8080/health
```
