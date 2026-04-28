# StudentZK — Testing Guide

Everything you can test today. Phase 1.5 (SD-JWT-VC + OID4VCI) is shipped. Phase 2 is feature-complete: KB-JWT device binding, BBS-2023 verifiable from the browser, DCQL format validation, Play Integrity anti-replay, and multi-type credentials. See `ROADMAP.md` for Phase 3 (liveness, photo binding, admin UI).

## Prerequisites

- **JDK 21** — `java -version` should print `21`.
- **Node 20+** — `node -v` should print `v20.x` or later.
- **Rust 1.75+** — `rustc --version`. Only needed for Flow C.
- **`curl` + `jq`** — only needed for the bash demo (`scripts/demo.sh`) and the OID4VCI curl recipes. On Windows, run `scripts/demo.ps1` (PowerShell-native) instead and skip both. Git Bash ships `curl`; install `jq` via `winget install jqlang.jq` or `choco install jq`.
- **Postgres** — skip if you use the `local` Spring profile (embedded). Otherwise see `README.md` → "Running locally — the real Postgres path".

First-time-only setup:
```bash
cd verifier-web && npm install     # pulls react, jose, pako, types
cd ../crypto-core  && cargo build  # ~1 min, fetches BLS12-381 + bbs_plus
```

## Start the stack

Three terminals, in order. Use the `local` profile on the issuer — it boots an embedded Postgres in-process and auto-activates the `dev-shortcut` profile so the `/dev/**` helpers exist.

**Terminal 1 — issuer**
```bash
cd issuer-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```
Wait for `Started StudentZkpApplication`. Note the line `studentzkp.admin.password not set — generated one for this boot: 'XXXX...'` if you'll be testing `/integrity/**`.

**Terminal 2 — verifier-web**
```bash
cd verifier-web
cp .env.example .env.local   # one-time
npm run dev
```
Vite serves the SPA on `http://localhost:5173`.

**Terminal 3 — your test commands**
Free for `scripts/demo.sh` (bash) or `scripts/demo.ps1` (PowerShell, Windows), curl, `cargo test`.

Stop everything with `Ctrl-C` in each terminal. The embedded Postgres shuts down with the issuer; nothing lingers.

---

## Flow A — Dev shortcut (one minute, end-to-end)

Skips the OID4VCI handshake. The issuer mints an SD-JWT-VC directly; the verifier-web validates it client-side.

```
scripts/demo.sh  ──POST /dev/credential/0036123456──>  issuer
       │                                                  │
       └──── SD-JWT-VC ───────────────────────────────────┘
       │
       └─> paste into http://localhost:5173 ──> verifier-web
                                                    ├── GET /.well-known/jwks.json   (signature)
                                                    ├── GET /statuslist/uni-2026.json (revocation)
                                                    └── recompute disclosure hashes  (tamper)
```

Run `bash scripts/demo.sh` (or `.\scripts\demo.ps1` on Windows — same output, copies the SD-JWT to your clipboard). Copy the `── SD-JWT-VC (compact) ──` block. Paste into the verifier UI. Click **Verify**.

### The 8 test scenarios

Convince yourself the four properties hold: (a) the signature is load-bearing, (b) selective disclosure actually hides what it says it hides, (c) disclosures can't be forged, (d) revocation propagates without an authenticated round-trip.

#### 1. Happy path
1. `bash scripts/demo.sh` (or `.\scripts\demo.ps1`) → copy the SD-JWT-VC.
2. Paste into `http://localhost:5173`, click **Verify**.
3. **Expect**: green "Credential verified", revealed table contains `is_student: true` and `age_equal_or_over: {"18": true}`, hidden disclosure count is nonzero.

#### 2. Privacy claim — name + DOB never travel
Decode the JWT payload manually:
```bash
SDJWT="<paste compact string>"
echo "${SDJWT%%~*}" | awk -F. '{print $2}' | tr '_-' '/+' | base64 -d 2>/dev/null | jq .
```
**Expect**: an `_sd` array of SHA-256 hashes plus `valid_until` and `status`. **Never** see `given_name`, `family_name`, `date_of_birth`, or `student_id` in plaintext anywhere.

#### 3. Tamper detection — flip a JWT byte
1. Mint a fresh credential.
2. Change one character in the JWT portion (before the first `~`). The last byte of the signature is easiest.
3. Click **Verify**.
4. **Expect**: red banner, error mentions `signature verification failed`.

#### 4. Tamper detection — forge a disclosure
1. Mint a fresh credential.
2. Take a `~`-segment, decode base64url to JSON `[salt, name, value]`. Change a value (e.g., `false` → `true`). Re-encode to base64url. Splice it back into the compact string.
3. Click **Verify**.
4. **Expect**: red banner with `Disclosure rejected: its hash is not present in the issuer-signed _sd array`.

#### 5. Revocation
1. `bash scripts/demo.sh` (or `.\scripts\demo.ps1`) → note the `Credential ID`.
2. Revoke it:
   ```bash
   curl -X POST http://localhost:8080/dev/credential/<credentialId>/revoke
   ```
3. Re-paste the SD-JWT-VC into the verifier, click **Verify**.
4. **Expect**: red "Credential REVOKED" banner. The verifier fetched the status list and inflated the bitstring.

The verifier made no authenticated call to the issuer — the status list is a public static resource.

#### 6. Offline / stale status list
Tests the "fail soft on revocation" policy.
1. Mint a fresh credential.
2. Devtools → Network → right-click any request → **Block request URL** → enter `http://localhost:8080/statuslist/uni-2026.json`.
3. Paste the SD-JWT-VC, click **Verify**.
4. **Expect**: green "Credential verified" with caveat `(status list unreachable — result shown against last-known state)`.

Signature check still passes (JWKS not blocked) — exactly what a flaky-uplink kiosk should do.

#### 7. Sanity — JWKS directly
```bash
curl -s http://localhost:8080/.well-known/jwks.json | jq .
```
**Expect**: one EC P-256 key, `use: sig`, `alg: ES256`, with the same `kid` as in any minted JWT's header. Restart the issuer and the `kid` survives — the key is persisted to `./.studentzkp/issuer-signing-key.jwk`.

#### 8. OID4VCI handshake walkthrough
See Flow B below.

---

## Flow A.5 — KB-JWT device-bound presentation (Phase 2)

Proves the credential lives on a specific phone. The wallet pins its StrongBox/TEE public key as the credential's `cnf` claim at issuance, then signs a Key-Binding JWT over the verifier's challenge before each presentation.

Requires the Android holder app (`holder-android/StudentZK`). Open in Android Studio and run on a device or emulator.

1. **Mint a device-bound credential.** In the wallet, tap **Add Credential**, enter `0036123456`, **Issue**. The wallet sends its StrongBox public JWK as `cnfJwk`; the issuer pins it into the SD-JWT-VC.
2. **Generate a verifier challenge.** Open `http://localhost:5173`. The page now shows a fresh `nonce` and an `audience` (= the verifier's origin). Leave **Require Key-Binding JWT** checked.
3. **Build the presentation.** In the wallet, open the credential, tap **Present**. Paste the nonce and audience from step 2. Tap **Generate**. The wallet shows a QR + a "Copy presentation" button — its output is `<sd-jwt>~<disclosures>~<kb-jwt>`.
4. **Verify.** Paste the presentation into the verifier and click **Verify**.
5. **Expect**: green "Credential verified" *and* a green "Device-bound (KB-JWT verified against StrongBox-pinned cnf key)" badge below it.

Failure scenarios to try:
- **Replay another nonce.** Click "Regenerate nonce" in the verifier, then paste the *previous* presentation. Expect: `KB-JWT nonce mismatch (replay or wrong challenge)`.
- **Drop the KB-JWT.** Paste only the SD-JWT body (everything before the last `~`). Expect: `Credential has a cnf claim (device-bound) but presentation is missing a KB-JWT`.
- **Tamper a disclosure.** Flip a character in one of the `~`-separated disclosures. Expect: `KB-JWT sd_hash does not match the presented disclosures` (the binding catches it before the disclosure-hash check would).

A non-bound dev credential (no `cnf`) from `scripts/demo.sh` paste-verifies as "Credential verified" with a yellow `⚠ No Key-Binding JWT — accepting unbound credential` notice. Toggle **Require Key-Binding JWT** off to suppress the warning.

---

## Flow B — Real OID4VCI handshake (Phase 1.5)

What an actual wallet drives. Three round-trips, plus a fourth that needs a JOSE library to sign a proof JWT.

```
1. POST /dev/credential-offer/{studentId}      → offer_id, pre_authorized_code, deep_link
2. GET  /credential-offer/{offer_id}           → credential_offer object
3. POST /token  (form-encoded pre-auth grant)  → access_token, c_nonce
4. POST /credential  (Bearer + proof JWT)      → SD-JWT-VC bound to holder's cnf key
```

### Steps 1–3 (curl is enough)

```bash
# 1. Mint an offer
OFFER=$(curl -sX POST http://localhost:8080/dev/credential-offer/0036123456)
echo "$OFFER" | jq .
# expect: { "offer_id":"...", "pre_authorized_code":"...", "credential_offer_uri":"...", "deep_link":"openid-credential-offer://..." }

# 2. Wallet fetches the offer JSON by reference
OFFER_ID=$(echo "$OFFER" | jq -r .offer_id)
curl -s http://localhost:8080/credential-offer/$OFFER_ID | jq .
# expect: credential_configuration_ids contains "UniversityStudent",
#         grants object contains the pre-authorized_code

# 3. Wallet redeems the pre-auth code
PRE_AUTH=$(echo "$OFFER" | jq -r .pre_authorized_code)
TOKEN=$(curl -s -X POST http://localhost:8080/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code' \
  --data-urlencode "pre-authorized_code=$PRE_AUTH")
echo "$TOKEN" | jq .
# expect: access_token, c_nonce, expires_in
```

### Step 4 (needs a JOSE library)

The wallet has to sign a proof JWT with its hardware-bound key. The shape is:

- **Header**: `{ "alg": "ES256", "typ": "openid4vci-proof+jwt", "jwk": <holder public JWK> }`
- **Payload**: `{ "aud": "https://issuer.studentzkp.hr", "iat": <unix-seconds>, "nonce": "<c_nonce>" }`

`aud` must equal `studentzkp.issuer.id` from `application.yml`, **not** the public base URL. The issuer validates: signature against `header.jwk`, `aud`, `nonce`, `iat` skew ≤ 5 min.

#### Minimal Python recipe (one-time `pip install python-jose cryptography`)
```python
import json, time, requests
from jose import jwk as jose_jwk, jwt
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization

ISSUER = "https://issuer.studentzkp.hr"            # aud claim
BASE   = "http://localhost:8080"                    # HTTP routing
OFFER  = requests.post(f"{BASE}/dev/credential-offer/0036123456").json()
TOK    = requests.post(f"{BASE}/token",
            data={"grant_type": "urn:ietf:params:oauth:grant-type:pre-authorized_code",
                  "pre-authorized_code": OFFER["pre_authorized_code"]}).json()

# Holder generates a fresh ES256 keypair (in real life, StrongBox-backed).
priv = ec.generate_private_key(ec.SECP256R1())
pub_jwk = json.loads(jose_jwk.construct(priv, algorithm="ES256").to_dict_json()
          if hasattr(jose_jwk.construct(priv, "ES256"), "to_dict_json")
          else json.dumps({"kty":"EC","crv":"P-256",
              "x": ..., "y": ...}))   # easier: use `jose.utils.long_to_base64`

proof = jwt.encode(
    claims={"aud": ISSUER, "iat": int(time.time()), "nonce": TOK["c_nonce"]},
    key=priv,
    algorithm="ES256",
    headers={"typ": "openid4vci-proof+jwt", "jwk": pub_jwk},
)

cred = requests.post(f"{BASE}/credential",
    headers={"Authorization": f"Bearer {TOK['access_token']}"},
    json={"format": "vc+sd-jwt", "proof": {"proof_type": "jwt", "jwt": proof}}).json()
print(cred["credential"])  # paste into the verifier UI
```

#### Or: use a real wallet
[walt.id wallet](https://walt.id/wallet) accepts arbitrary OID4VCI deep-links. QR-encode `OFFER.deep_link`, scan it from the wallet, complete the flow. The wallet completes step 4 against your localhost issuer (you'll need ngrok or LAN routing for a phone-side wallet).

### What to verify after step 4

1. The returned `credential` is a compact SD-JWT-VC — paste it into `http://localhost:5173` and confirm it verifies.
2. Decode the JWT body; the `cnf.jwk` claim should equal the public JWK you signed the proof with — that's the device-binding the verifier will eventually enforce via KB-JWT (Phase 2).
3. Try `POST /credential` again with the same access token. **Expect**: `400 Access_token already used`. Single-use enforcement.
4. Try `POST /token` again with the same pre-authorized code. **Expect**: `400 Pre-authorized_code already redeemed`.

---

## Flow D — BBS-2023 proofs in the browser (Phase 2)

Tests unlinkable credentials: two presentations of the same BBS credential produce cryptographically different proofs.

### Test unlinkability

1. **Mint a BBS credential** via `bash scripts/demo.sh` (or `.ps1`). The output now includes both `-- SD-JWT-VC` and `-- W3C VCDM 2.0 BBS-2023 credential --` sections.

2. **Paste the BBS credential into the verifier.** Copy the `-- W3C VCDM 2.0 --` block (starts with `{"@context"...`, ends with `"proof":...}`). Paste into the verifier at `http://localhost:5173`.

3. **Verify the BBS credential.** Click **Verify**. 
   - **Expect**: green "Credential verified" badge.
   - Format badge shows: `Format: BBS-2023`.
   - **Expect**: DCQL validation badge (see Flow E below).

4. **Mint two independent presentations** to demonstrate unlinkability:
   - Mint the same BBS credential a second time: `bash scripts/demo.sh` (two separate runs).
   - In the verifier UI, under **3. Unlinkability Demo**, paste the first BBS credential into the disabled "Presentation 1" field (it's pre-filled from step 2).
   - Paste the second BBS credential into "Presentation 2".
   - Click **Compare proofs**.
   - **Expect**: green "Proofs are DIFFERENT — BBS-2023 unlinkability" badge. The two sha256 hashes below are visually different.

5. **Compare with SD-JWT-VC linkability:**
   - Paste the same SD-JWT-VC credential twice into the comparison fields.
   - **Expect**: red "Proofs are IDENTICAL — SD-JWT-VC linkability" badge. The sha256 hash is the same for both because the issuer's JWS signature is unchanged.

### Why this matters

Two presentations of the same BBS credential to different verifiers produce unrelated proofs. A verifier cannot correlate presentations — even if they hold all the cryptographic material. SD-JWT-VC fails this: the issuer's signature is identical, so any two verifiers seeing the same credential know it's a replay.

---

## Flow E — DCQL format validation (Phase 2)

Tests that the verifier rejects credentials that don't meet the request constraints.

### Happy path

1. Mint and verify any credential (SD-JWT-VC or BBS) as in Flows A or D.
2. **Expect**: green "Meets DCQL requirements (format, type, and all required claims disclosed)" badge.

### Failure scenarios

1. **Wrong credential type** (manual test):
   - Edit `verifier-web/src/App.tsx`, change `meta.vct_values` from `['https://studentzk.eu/types/student/v1']` to `['https://example.com/wrong-type']`.
   - Mint a student credential and verify.
   - **Expect**: red badge listing `vct: Credential type "https://studentzk.eu/types/student/v1" not in accepted types: https://example.com/wrong-type`.

2. **Missing required claim** (manual test):
   - Edit `verifier-web/src/App.tsx`, add a third claim: `{ path: ['nonexistent_claim'] }`.
   - Verify any credential.
   - **Expect**: red badge listing `claim: Required claim "nonexistent_claim" is not disclosed in the presentation`.

3. **Format mismatch** (manual test):
   - Edit `verifier-web/src/App.tsx`, change `format` from `['vc+sd-jwt', 'vc+bbs']` to `['vc+sd-jwt']` (reject BBS).
   - Mint a BBS credential and verify.
   - **Expect**: red badge listing `format: Credential format "vc+bbs" not in accepted formats: vc+sd-jwt`.

---

## Flow F — Age credential (multi-type) (Phase 3 preview)

Tests that the platform issues different credential types from the same student database, proving extensibility.

### Mint an age credential

```bash
curl -X POST http://localhost:8080/dev/credential/age/0036123456 | jq .
```

Or via the Python OID4VCI recipe:
```python
# Same setup as Flow B, then:
OFFER = requests.post(f"{BASE}/dev/credential-offer/0036123456", 
                      json={"credentialType": "age"}).json()
# continue with /token + /credential flow
```

**Expect**: an SD-JWT-VC or BBS-2023 credential with:
- `vct: "https://studentzk.eu/types/age/v1"`
- `credentialSubject` contains only `age_equal_or_over`, **never** student_id or name hashes
- Same revocation list, same validity model

### Verify both types in one wallet

1. Mint a student credential: `curl -X POST http://localhost:8080/dev/credential/0036123456`.
2. Mint an age credential: `curl -X POST http://localhost:8080/dev/credential/age/0036123456`.
3. Paste each into the verifier separately. Both verify with the same signature + revocation checks, but different `vct` and `credentialSubject`.
4. **Expect**: the DCQL badge adapts to each type. If you add a third credential type later (e.g., library/v1), the same verifier, issuer, and holder code work unchanged.

---

## Flow G — JMBAG validation

Tests that the issuer rejects invalid student IDs and only issues to real JMBAGs (10-digit format).

### Valid JMBAG

```bash
curl -X POST http://localhost:8080/dev/credential/0036123456 | jq .
# expect: credential issued
```

### Invalid JMBAG — format

```bash
# Too short
curl -X POST http://localhost:8080/dev/credential/123 | jq .
# expect: 400 Bad Request, "Invalid JMBAG format: must be 10 digits"

# Non-numeric
curl -X POST http://localhost:8080/dev/credential/ABC1234567 | jq .
# expect: 400 Bad Request

# Too long
curl -X POST http://localhost:8080/dev/credential/01234567890 | jq .
# expect: 400 Bad Request
```

### Invalid JMBAG — not in registry

```bash
# Valid format (10 digits) but JMBAG doesn't exist in the students table
curl -X POST http://localhost:8080/dev/credential/9999999999 | jq .
# expect: 403 Forbidden, "JMBAG is not valid in the university student registry"
```

**Why this matters**: The issuer validates JMBAGs in two layers:
1. **Format**: exactly 10 digits (catches typos, wrong-length inputs)
2. **Registry**: existence in the seeded `students` table (prevents issuing to non-existent students)

In production, the registry layer plugs into the actual University of Zagreb student database (LDAP, API, CSV sync, etc.) via the configurable `StudentRegistryService`.
```

---

## Flow C — Rust crypto-core (BBS+)

Pure unit tests; no issuer or DB needed.

```bash
cd crypto-core
cargo test
```

**Expect** eight tests, all green:

| Test | What it proves |
|---|---|
| `signs_and_verifies_full_disclosure` | Sign 6 attributes, derive a proof disclosing all of them, verify it. |
| `selective_disclosure_roundtrip` | Sign 6 attributes, disclose only `is_student` + `age_over_18`, verify with the verifier seeing only those two messages. |
| `proof_rejected_with_wrong_nonce` | Replay protection — proof bound to nonce-A fails when verified under nonce-B. |
| `proof_rejected_with_tampered_disclosed_message` | Tamper detection — flipping a disclosed byte invalidates the proof. |
| `unlinkability_two_proofs_differ` | Two proofs derived from the same signature are byte-different. **This is the BBS+ "wow" property** — vs SD-JWT-VC where the issuer signature is identical across presentations. |
| `ffi::tests::ffi_full_roundtrip` | Same as `selective_disclosure_roundtrip`, but driven through the C-ABI (`studentzkp_bbs_*` symbols) to prove JNA/UniFFI consumers will see the same results. |
| `ffi::tests::last_error_is_set_on_invalid_input` | Rust `Err(...)` strings travel across the FFI boundary into a thread-local error retrievable via `studentzkp_last_error`. |
| `ffi::tests::null_out_param_returns_null_pointer_error` | Passing `NULL` for a required out-parameter returns a clean status code instead of segfaulting. |

### Building the cdylib for the issuer JVM and Android

Once the FFI tests pass, build the shared library and place it where each consumer expects:

```bash
bash scripts/build-crypto.sh           # host JVM + Android (skips Android if NDK absent)
bash scripts/build-crypto.sh host      # host JVM only
bash scripts/build-crypto.sh android   # Android only (needs cargo-ndk + NDK)
```

PowerShell equivalent: `.\scripts\build-crypto.ps1` with the same `host` / `android` / `all` modes.

Outputs:
- **Host JVM**: `issuer-backend/build/native/studentzkp_crypto.{dll,so,dylib}`. The issuer's `bootRun` and `test` tasks set `jna.library.path` to that directory automatically.
- **Android**: `holder-android/StudentZK/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64,x86}/libstudentzkp_crypto.so`. AGP auto-bundles those into the APK. The Android prerequisite is a one-time `cargo install cargo-ndk` plus an NDK install (set `ANDROID_NDK_HOME`; Android Studio's SDK Manager places it under `<sdk>/ndk/<version>`).

---

## Flow H — Play Integrity anti-replay (Phase 2, prod-only)

Tests device attestation and nonce-based replay prevention. Only active in prod deployments with Google Play Integrity credentials configured.

### Dev mode (stub)

In dev (default), the stub `StubIntegrityService` accepts any token:

```bash
# Issue a nonce
NONCE=$(curl -s -X POST http://localhost:8080/integrity/nonce \
  -H 'Content-Type: application/json' \
  -d '{"subjectDid":"urn:dev:0036123456"}' | jq -r .nonce)

# Verify a (fake) token
curl -s -X POST http://localhost:8080/integrity/verify \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"fake-token\",\"requestHash\":\"$(echo -n 'test' | sha256sum | cut -d' ' -f1 | base64 -w0)\"}" | jq .
# expect: { "ok": true, "reasons": ["stub-service: token not validated against Google"] }
```

### Prod mode (real Google API)

To test against real Google Play Integrity:

1. **Configure Google credentials.** Set environment variables:
   ```bash
   export PLAY_INTEGRITY_PROJECT_NUMBER="<your-google-cloud-project-number>"
   export PLAY_INTEGRITY_SA_JSON='{"type":"service_account",...}'  # full JSON key
   ```

2. **Build with Google API enabled.** Uncomment in `issuer-backend/build.gradle.kts`:
   ```gradle
   implementation("com.google.api-client:google-api-client:2.2.0")
   implementation("com.google.apis:google-api-services-playintegrity:v1-rev20231219-2.0.0")
   ```

3. **Run in prod profile.** `./gradlew bootRun --args='--spring.profiles.active=prod'` loads the real `PlayIntegrityService`.

4. **Test from the Android app.** The holder-android wallet, when issuing or presenting:
   - Calls `GET /integrity/nonce` to fetch a challenge
   - Constructs a Play Integrity token on the device (via Google Play Services)
   - Submits the token to `POST /integrity/verify` with a request hash (SHA-256 of the presentation)
   - The issuer verifies against Google, checks device integrity (rejects ROOTED/EMULATOR), checks app integrity (PLAYS_CERTIFIED), and records the verdict
   - **Expect**: rooted/emulator devices refused; genuine Play-signed apps accepted

5. **Replay protection**: The nonce is unique per request and enforced in `integrity_assertions` table with a `UNIQUE` constraint. Reusing the same nonce rejects with `Nonce replay detected`.

---

## Reset / cleanup between runs

| What | How |
|---|---|
| Wipe in-flight OID4VCI offers / tokens | Restart the issuer (state is in-memory). |
| Wipe credentials + integrity log, keep seed data | If using `local` profile: restart the issuer. If using Docker: `docker exec studentzkp-pg psql -U studentzkp -d studentzkp -c 'TRUNCATE credential, integrity_assertions RESTART IDENTITY CASCADE;'` |
| Rotate the issuer signing key | `rm ./.studentzkp/issuer-signing-key.jwk` then restart. **All previously-issued credentials will fail verification afterward.** |
| Reset the status-list bit allocation | Hard reset the DB. Sequence values aren't reset by `TRUNCATE` alone. |
| Hard reset (Docker path) | `docker rm -f studentzkp-pg`, rerun the README's docker run step. |

---

## Common issues

- **`tsc` says TS2875 / "react/jsx-runtime not found"** — `node_modules/` is missing. `cd verifier-web && npm install`.
- **`bootRun` logs `Connection refused` to Postgres** — using the Docker path with the container not yet up. `docker ps` to confirm; logs via `docker logs studentzkp-pg`. Or switch to the `local` profile to avoid Postgres entirely.
- **`Flyway reports checksum mismatch`** — a migration file was edited after first apply. In dev: hard-reset the DB. Never edit an applied migration in prod.
- **OID4VCI `400 Pre-authorized_code already redeemed` on first try** — you ran `/token` twice with the same code. Codes are single-use; mint a fresh offer.
- **OID4VCI `400 Proof JWT aud must be ...`** — your proof's `aud` is wrong. Use `studentzkp.issuer.id` from `application.yml` (default `https://issuer.studentzkp.hr`), not the actual base URL where the issuer is reachable.
- **OID4VCI `400 Proof JWT iat too far from now`** — clock skew > 5 minutes. Sync your clock.
- **`Cargo.lock` conflicts in crypto-core** — `cargo clean && cargo build`.
- **`scripts/demo.sh` (or `.ps1`) gets a 401/403 from `/dev/credential/...`** — issuer running without the `local` (or `dev-shortcut`) profile. Restart with `--args='--spring.profiles.active=local'` (or set Active profiles = `local` in IntelliJ). The boot log should show `activeProfiles=local,dev-shortcut, devShortcutPermit=true`.
- **`bash scripts/demo.sh` exits silently with `set: pipefail: invalid option`** — the `bash` on your PATH is actually a POSIX `sh` (BusyBox/dash). Use `scripts/demo.ps1` instead, or install Git Bash and run via its full path.

---

## Where to look next

- `README.md` — running the stack, plus a few scenarios with more prose.
- `docs/api.md` — every endpoint, request/response schema, error codes.
- `docs/architecture.md` — the v2 architecture diagram.
- `docs/threat-model.md` — anti-abuse layers and what's left.
- `ROADMAP.md` — what's done vs deferred, with file pointers per task.
- `final_plan_md.md` — the authoritative spec.
