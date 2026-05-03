# StudentZK — Setup Guide

**Project:** StudentZK  
**Date:** May 2026

This guide covers everything needed to go from a blank machine to a fully running StudentZK stack. No prior environment is assumed.

---

## 1. Prerequisites

Install these tools before starting:

| Tool | Required version | How to verify |
|---|---|---|
| JDK (Temurin recommended) | 21 | `java -version` → must print `21` |
| Node.js | 20+ | `node -v` → must print `v20.x` or later |
| Rust + Cargo | 1.75+ | `rustc --version` |
| Git | Any | `git --version` |
| curl | Any | `curl --version` |
| jq | Any | `jq --version` (optional — used by demo scripts) |

Only needed if building for Android or cross-compiling the Rust library:

- **Android Studio** (latest stable) with Android NDK r27 installed via SDK Manager → SDK Tools → NDK
- `cargo install cargo-ndk`

---

## 2. Clone the Repository

```bash
git clone <repository-url>
cd student-zkp
```

---

## 3. Build the Cryptographic Core (Rust)

All other components depend on the Rust library. Build it first.

```bash
cd crypto-core
cargo test        # runs all 8 unit tests — all must pass
cd ..
```

Expected output: `test result: ok. 8 passed; 0 failed`

The 8 tests cover: full disclosure roundtrip, selective disclosure, replay rejection (wrong nonce), tamper detection, unlinkability (two proofs of the same credential differ byte-for-byte), FFI full roundtrip, error propagation across FFI, and null-pointer handling.

Now build the native shared library for the host JVM:

```bash
# Linux / macOS
bash scripts/build-crypto.sh host

# Windows (PowerShell)
.\scripts\build-crypto.ps1 host
```

Output: `issuer-backend/build/native/studentzkp_crypto.dll` (Windows), `libstudentzkp_crypto.so` (Linux), or `libstudentzkp_crypto.dylib` (macOS). The issuer picks this up automatically via `jna.library.path` — no configuration needed.

To also build for Android (requires NDK and `cargo-ndk`):

```bash
bash scripts/build-crypto.sh android
# or build both:
bash scripts/build-crypto.sh
```

Android `.so` files land at `holder-android/StudentZK/app/src/main/jniLibs/<abi>/libstudentzkp_crypto.so` and are bundled into the APK automatically by Android Gradle Plugin.

---

## 4. Start the Issuer Backend

### Option A — Embedded PostgreSQL (recommended for development)

Zero database installation needed. The issuer boots its own embedded Postgres 16 in-process.

**Linux / macOS:**
```bash
cd issuer-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

**Windows (PowerShell):**
```powershell
cd issuer-backend
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

**First boot only:** Zonky downloads a PostgreSQL 16 binary (~60 MB) into `~/.embedpostgresql/`. Takes 15–30 seconds on a good connection. Subsequent starts are instant.

Wait for: `Started StudentZkpApplication`

Also note this log line and save the password:
```
studentzkp.admin.password not set — generated one for this boot: 'XXXX...'
```

The issuer is now running at `http://localhost:8080`. The `local` profile automatically activates `dev-shortcut` as well, enabling the `/dev/**` helper endpoints used by the demo scripts.

### Option B — Docker PostgreSQL

```bash
docker run -d --name studentzkp-pg \
  -e POSTGRES_DB=studentzkp \
  -e POSTGRES_USER=studentzkp \
  -e POSTGRES_PASSWORD=studentzkp \
  -p 5432:5432 \
  postgres:16

# Wait ~5 seconds, then:
cd issuer-backend
./gradlew bootRun --args='--spring.profiles.active=dev-shortcut'
```

Flyway applies all migrations automatically on first boot. On subsequent boots: "No migration necessary."

### Option C — Native PostgreSQL (Windows)

```powershell
winget install PostgreSQL.PostgreSQL.16
# Add C:\Program Files\PostgreSQL\16\bin\ to PATH, then:
psql -U postgres -c "CREATE USER studentzkp WITH PASSWORD 'studentzkp';"
psql -U postgres -c "CREATE DATABASE studentzkp OWNER studentzkp;"
```

Then start the issuer: `.\gradlew.bat bootRun`

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Connection refused` to Postgres | Container not ready | `docker ps` to confirm status; wait a few seconds |
| `password authentication failed` | Container restarted with different env vars (Postgres ignores env on existing volume) | `docker rm -f studentzkp-pg` and rerun |
| `Flyway checksum mismatch` | Migration file edited after first apply | Hard-reset the DB |
| `401/403` from `/dev/**` | `dev-shortcut` profile not active | Restart with `--spring.profiles.active=local` |

---

## 5. Start the Verifier Web App

```bash
cd verifier-web
cp .env.example .env.local    # one-time; sets VITE_ISSUER_BASE_URL
npm install
npm run dev
```

Vite serves the SPA at `http://localhost:5173`.

---

## 6. Run the End-to-End Demo

With the issuer (Terminal 1) and verifier (Terminal 2) running, open a third terminal:

```bash
# Linux / macOS
bash scripts/demo.sh

# Windows (PowerShell)
.\scripts\demo.ps1
```

The script:
1. Issues an SD-JWT-VC and a BBS-2023 credential for the seeded student `0036123456`.
2. Prints the compact SD-JWT-VC with all disclosures decoded to `[salt, name, value]`.
3. Prints the full W3C VCDM 2.0 BBS credential JSON.

**Verify:** Copy the SD-JWT-VC string → paste into `http://localhost:5173` → click **Verify**.

Expected result: Green "Credential verified" banner. `is_student: true` and `age_equal_or_over: {"18": true}` are shown. The student's name, date of birth, and student number are nowhere in the transmitted credential.

---

## 7. Set Up the Android Wallet

1. Open Android Studio.
2. Open the existing project at `holder-android/StudentZK/`.
3. Let Gradle sync complete.
4. The Rust `.so` files from step 3 are already placed at `holder-android/StudentZK/app/src/main/jniLibs/`. No additional steps.
5. Connect an Android device (API 29 / Android 10 or higher) or start an emulator.
6. Press **Run** (▶). The StudentZK wallet opens with an empty credential list.
7. Tap **Add Credential**, enter student ID `0036123456`, tap **Issue**.
   - Emulator: wallet contacts issuer at `http://10.0.2.2:8080`
   - Physical device: wallet contacts issuer at `http://<your-local-ip>:8080`
8. Once issued, tap the credential card to see full details.

### Verify a wallet presentation

1. Open `http://localhost:5173` in a desktop browser.
2. Click **Generate Challenge** — a fresh `nonce` and `audience` appear.
3. In the wallet, open the credential and tap **Present**. Enter the nonce and audience. Tap **Generate**. A QR code appears.
4. In the verifier, click the camera icon and scan the wallet's screen.
5. Expected: Green "Credential verified" banner and a "Device-bound (KB-JWT verified against StrongBox-pinned cnf key)" badge.

---

## 8. Test Scenarios

### Tamper detection — flip a JWT byte

Change any character in the SD-JWT-VC's JWT portion (before the first `~`) and click Verify.  
**Expected:** Red banner — `signature verification failed`.

### Tamper detection — forge a disclosure

Decode a `~`-separated segment from base64url to `[salt, name, value]`, change the value, re-encode, splice it back. Click Verify.  
**Expected:** Red banner — `Disclosure rejected: its hash is not present in the issuer-signed _sd array`.

### Revocation

```bash
curl -X POST http://localhost:8080/dev/credential/<credentialId>/revoke
```

Re-paste the same SD-JWT-VC and click Verify.  
**Expected:** Red "Credential REVOKED" banner.

### KB-JWT replay

Generate a challenge in the verifier, complete a presentation, then click "Regenerate nonce" and re-paste the same presentation.  
**Expected:** Red banner — `KB-JWT nonce mismatch (replay or wrong challenge)`.

### BBS+ unlinkability demo

Run `bash scripts/demo.sh` twice. In the verifier UI under **Unlinkability Demo**, paste the two BBS credentials.  
**Expected:** Green badge — "Proofs are DIFFERENT — BBS-2023 unlinkability". SHA-256 hashes are visibly different.

Paste the same SD-JWT-VC twice.  
**Expected:** Red badge — "Proofs are IDENTICAL — SD-JWT-VC linkability". The issuer's JWS signature is unchanged across presentations.

### Age credential (second credential type)

```bash
curl -X POST http://localhost:8080/dev/credential/age/0036123456 | jq .
```

**Expected:** SD-JWT-VC with `vct: "https://studentzk.eu/types/age/v1"` and only `age_equal_or_over` in `credentialSubject` — no student ID, no name hashes. Same revocation list, same validity model.

### JMBAG validation

```bash
# Valid
curl -X POST http://localhost:8080/dev/credential/0036123456      # 200 OK

# Too short
curl -X POST http://localhost:8080/dev/credential/123             # 400 Bad Request

# Valid format but not in registry
curl -X POST http://localhost:8080/dev/credential/9999999999      # 403 Forbidden
```

---

## 9. Database Schema

Flyway migrations in `issuer-backend/src/main/resources/db/migration/` apply in order:

| Migration | Content |
|---|---|
| `V1__initial_schema.sql` | `credential_type`, `credential`, `students` tables |
| `V2__seed_data.sql` | Seeds student `0036123456` (Ana Horvat, FER) and `student/v1` credential type |
| `V3__phase1_adjustments.sql` | Makes `cnf_key_jwk` nullable for Phase 1 backward compatibility |
| `V4__status_idx_sequence.sql` | Replaces racy `MAX()+1` with a PostgreSQL sequence for status index allocation |
| `V5__add_age_credential_type.sql` | Seeds `age/v1` credential type |
| `V6__integrity_assertions.sql` | `integrity_assertions` table for Play Integrity nonce audit log |

### Useful DB commands (Docker path)

```bash
# Check all credentials
docker exec studentzkp-pg psql -U studentzkp -d studentzkp \
  -c 'SELECT id, status_idx, revoked FROM credential;'

# Soft reset (keeps seed data)
docker exec studentzkp-pg psql -U studentzkp -d studentzkp \
  -c 'TRUNCATE credential, integrity_assertions RESTART IDENTITY CASCADE;'

# Hard reset
docker rm -f studentzkp-pg    # then rerun the docker run command
```

---

## 10. Configuration Reference

| Property / Env var | Default | Purpose |
|---|---|---|
| `STUDENTZKP_ADMIN_PASSWORD` | auto-generated per boot | Password for `/integrity/**` and `/admin/**` |
| `ISSUER_PUBLIC_BASE_URL` | `http://localhost:8080` | Issuer URL embedded in credentials (must be reachable by verifiers) |
| `studentzkp.issuer.keyPath` | `./.studentzkp/issuer-signing-key.jwk` | ES256 signing key persistence path |
| `studentzkp.issuer.bbsKeyPath` | `./.studentzkp/issuer-bbs-key.json` | BBS+ key persistence path |
| `studentzkp.statusList.capacity` | `131072` | Total bitstring capacity (number of credentials) |
| `DB_USERNAME` / `DB_PASSWORD` | `studentzkp` / `studentzkp` | Database credentials |
| `PLAY_INTEGRITY_PROJECT_NUMBER` | (unset) | Google Cloud project number for real Play Integrity |
| `PLAY_INTEGRITY_SA_JSON` | (unset) | Service account key JSON for Play Integrity decoding |

Both signing keys are persisted to disk on first boot and reloaded on restart. Delete the files under `./.studentzkp/` to rotate keys — all previously issued credentials will then fail signature verification.

---

## 11. API Reference

**Base URL (dev):** `http://localhost:8080`

### Public endpoints (no authentication)

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Liveness probe → `{ "status": "ok" }` |
| `GET` | `/.well-known/jwks.json` | Issuer ES256 JWKS — verifiers cache this to verify SD-JWT-VC signatures |
| `GET` | `/.well-known/studentzkp-bbs-key.json` | Issuer BBS+ public key — verifiers fetch this once |
| `GET` | `/statuslist/uni-2026.json` | IETF Token Status List revocation bitstring |
| `GET` | `/credential-offer/{offerId}` | OID4VCI credential offer JSON (wallet fetches after scanning the QR) |
| `POST` | `/token` | OID4VCI pre-authorized code → `access_token` + `c_nonce` |
| `POST` | `/credential` | OID4VCI credential issuance — requires Bearer token + proof JWT |

### Authenticated endpoints (HTTP Basic)

| Method | Path | Description |
|---|---|---|
| `POST` | `/integrity/nonce` | Issue a single-use Play Integrity nonce |
| `POST` | `/integrity/verify` | Decode and validate a Play Integrity token |

### Dev-only endpoints (active only under `dev-shortcut` Spring profile)

| Method | Path | Description |
|---|---|---|
| `POST` | `/dev/credential/{studentId}` | Issue a credential directly, skipping OID4VCI |
| `POST` | `/dev/credential/{credentialId}/revoke` | Revoke a credential by UUID |
| `POST` | `/dev/credential-offer/{studentId}` | Create a credential offer and return the `openid-credential-offer://` deep-link |
| `POST` | `/dev/credential/age/{studentId}` | Issue an age-only credential |

### SD-JWT-VC payload (decoded JWT body)

```json
{
  "iss": "https://issuer.studentzkp.hr",
  "iat": 1745520000,
  "exp": 1777056000,
  "vct": "https://studentzk.eu/types/student/v1",
  "sub": "did:jwk:...",
  "cnf": { "jwk": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." } },
  "_sd": [ "<sha256-base64url-of-disclosure>", "..." ],
  "_sd_alg": "sha-256",
  "valid_until": "2027-04-25",
  "status": {
    "status_list": { "idx": 7, "uri": "https://issuer.studentzkp.hr/statuslist/uni-2026.json" }
  }
}
```

Each `~`-separated disclosure trailer is `base64url(JSON.stringify([salt, name, value]))`.

### OID4VCI quick recipes

```bash
# Mint + verify in one step (dev shortcut)
curl -sX POST http://localhost:8080/dev/credential/0036123456 | jq .sdJwt

# Full OID4VCI flow (steps 1–3, step 4 needs a JOSE lib to sign the proof JWT)
OFFER=$(curl -sX POST http://localhost:8080/dev/credential-offer/0036123456)
PA=$(echo "$OFFER" | jq -r .pre_authorized_code)
curl -sX POST http://localhost:8080/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code' \
  --data-urlencode "pre-authorized_code=$PA" | jq .

# Revoke + observe
CRED_ID=$(curl -sX POST http://localhost:8080/dev/credential/0036123456 | jq -r .credentialId)
curl -sX POST "http://localhost:8080/dev/credential/$CRED_ID/revoke" | jq .
curl -s http://localhost:8080/statuslist/uni-2026.json | jq .
```
