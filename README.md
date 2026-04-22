# StudentZK

Privacy-preserving, machine-verifiable credentials — built on BBS+ and SD-JWT-VC, aligned with eIDAS 2.0 / EUDI Wallet.

StudentZK proves a **predicate** ("is a student", "is 18+") without revealing the holder's name, DOB, institution, or student number. The verifier is a static web page any kiosk or cashier can open — no integration, no account, no data collected.

See `final_plan_md.md` for the full technical spec, `docs/architecture.md` for the v2 diagram, and `docs/threat-model.md` for the anti-abuse story.

## Modules

| Module | Stack | Role |
|---|---|---|
| `issuer-backend` | Kotlin 2.1 · Spring Boot 3.4 · JVM 21 | OID4VCI issuer, credential-type registry, Play Integrity gateway |
| `verifier-web` | React 19 · Vite 6 · TypeScript 5.7 | OID4VP verifier (SD-JWT-VC + BBS WASM), client-side only |
| `holder-android` | Kotlin · Compose · StrongBox ES256 | Holder wallet (create in Android Studio) |
| `crypto-core` | Rust (`docknetwork/crypto`) · UniFFI | Shared BBS+ core — consumed by issuer (JNA) and holder (UniFFI) |

## Prerequisites

Minimum for the Phase 1 happy path (everything in this README):

- **JDK 21** (Temurin recommended). Verify: `java -version` prints `21`.
- **Node 20+** (for the verifier-web SPA). Verify: `node -v` prints `v20` or later.
- **Bash + `curl` + `jq`** (for `scripts/demo.sh`). On Windows, Git Bash ships with `curl`; `jq` via `winget install jqlang.jq` or `choco install jq`.

Only needed for later phases (skip for now):

- Rust 1.75+ + Android NDK r27 — `holder-android` + `crypto-core` (Phase 2/3)
- A separately-installed Postgres 16 — only if you don't want the embedded path below
- Google Cloud project with Play Integrity API — Phase 3 anti-abuse

## Running locally — the easy path

This is the recommended way to test on your own machine. Zero Postgres install, zero Docker. The issuer boots an embedded Postgres 16 inside its own JVM under the `local` Spring profile.

> **First boot only:** Zonky downloads a Postgres 16 binary (~60MB) into `~/.embedpostgresql/`. This takes 15–30 s on a good connection. Subsequent boots are instant. If you're behind a corporate proxy, set `HTTPS_PROXY` before running. Windows anti-virus may scan the binary on first launch; that's fine, just slower.

Three terminals, in order.

**Terminal 1 — issuer**
```bash
cd issuer-backend
./gradlew bootRun --args='--spring.profiles.active=local'
```
Wait for `Started StudentZkpApplication`. The issuer is now on <http://localhost:8080>.

**Terminal 2 — verifier**
```bash
cd verifier-web
cp .env.example .env.local
npm install
npm run dev
```
Vite serves the SPA on <http://localhost:5173>.

**Terminal 3 — mint + verify**
```bash
bash scripts/demo.sh
```
This POSTs to `/dev/credential/0036123456`, prints the SD-JWT-VC plus each disclosure decoded to `[salt, name, value]`, and reminds you to paste the SD-JWT-VC into the verifier. You'll see `is_student=true` and `age_equal_or_over.18=true`; the name/DOB/student-number hashes stay bound to the credential but never leave your browser.

Stop everything with `Ctrl-C` in each terminal — the embedded Postgres shuts down with the issuer, nothing lingers.

## Running locally — the "real Postgres" path

Skip this unless you want to run the issuer exactly as it will run in production — against an externally-managed Postgres. The protocol flow is identical to the easy path; only the database lifecycle differs. Three backing options: **Docker**, **native Windows**, **native macOS/Linux**. The issuer expects a DB named `studentzkp` owned by a user `studentzkp` with password `studentzkp` (override via `DB_USERNAME` / `DB_PASSWORD` env vars).

### Full walkthrough with Docker

Prereqs: Docker Desktop (macOS/Windows) or `docker` CLI + daemon (Linux). Verify: `docker --version` and `docker ps` both work.

**1. Start the container.** `-d` runs detached, `--name` labels it so we can reference it later, `-p 5432:5432` binds the Postgres port to your host.
```bash
docker run -d --name studentzkp-pg \
  -e POSTGRES_DB=studentzkp \
  -e POSTGRES_USER=studentzkp \
  -e POSTGRES_PASSWORD=studentzkp \
  -p 5432:5432 \
  postgres:16
```
If port 5432 is already taken (another Postgres on the host?), remap to e.g. `-p 5433:5432` and set `DB_URL=jdbc:postgresql://localhost:5433/studentzkp` before `bootRun`.

**2. Wait for Postgres to finish starting.** First boot initializes the data directory; takes ~3–5 s. Confirm:
```bash
docker logs studentzkp-pg --tail 5
```
Healthy output ends with `database system is ready to accept connections`. If you see that twice (bootstrap + restart), it's fully ready.

**3. Sanity check the connection.** From inside the container — no host `psql` needed:
```bash
docker exec studentzkp-pg psql -U studentzkp -d studentzkp -c '\conninfo'
```
Expect: `You are connected to database "studentzkp" as user "studentzkp"`.

**4. Start the issuer — without the `local` profile.** Flyway auto-applies all migrations on first boot.
```bash
cd issuer-backend
./gradlew bootRun
```
Expect log lines like `Successfully applied 3 migrations to schema "public"` followed by `Started StudentZkpApplication`. On a second run you'll see `No migration necessary`.

**5. Start the verifier.**
```bash
cd verifier-web
cp .env.example .env.local
npm install
npm run dev
```

**6. Mint + verify** — same as the easy path:
```bash
bash scripts/demo.sh
```
Copy the SD-JWT-VC → paste into <http://localhost:5173> → **Verify**.

#### Day-to-day lifecycle

| What you want | Command |
|---|---|
| Stop Postgres (keeps data) | `docker stop studentzkp-pg` |
| Start it again | `docker start studentzkp-pg` |
| Tail the logs | `docker logs -f studentzkp-pg` |
| Open a psql shell | `docker exec -it studentzkp-pg psql -U studentzkp` |
| Check what credentials exist | `docker exec studentzkp-pg psql -U studentzkp -d studentzkp -c 'SELECT id, status_idx, revoked FROM credential;'` |
| Fully delete and reset (⚠️ wipes data) | `docker rm -f studentzkp-pg` then rerun step 1 |

#### Resetting between test scenarios

The 7 test scenarios in "Testing it as intended" mostly work on independently-minted credentials, so you don't need to reset the DB between them. But if you want a clean slate (e.g. to re-run the revocation scenario with a fresh `status_idx`):

```bash
# Soft reset: truncate credentials + integrity log, keep seed data
docker exec studentzkp-pg psql -U studentzkp -d studentzkp -c \
  'TRUNCATE credential, integrity_assertions RESTART IDENTITY CASCADE;'

# Hard reset: nuke the container + volume, re-run step 1
docker rm -f studentzkp-pg
```

After a hard reset, restart the issuer — Flyway re-runs all migrations. The issuer's signing key is regenerated too (see ROADMAP → Known issues), so any previously-minted credentials will fail signature verification.

#### Troubleshooting

- **`bootRun` logs `Connection refused`** — container not listening yet. Run `docker ps` to confirm status `Up`, then retry after a few seconds. If it never comes up, `docker logs studentzkp-pg` will show why (common cause: port 5432 already bound on host).
- **`bootRun` logs `FATAL: password authentication failed`** — you started the container once with a different password, then rebooted with env vars that Postgres ignores on second boot (env vars only take effect on a fresh volume). Fix: `docker rm -f studentzkp-pg` and rerun step 1.
- **Flyway reports `checksum mismatch`** — a migration file was edited after first apply. During development, reset the DB (hard reset above); never edit an applied migration in prod.

### Native install — Windows

```powershell
winget install PostgreSQL.PostgreSQL.16
```
Then from a shell (add `C:\Program Files\PostgreSQL\16\bin\` to PATH or use the full path):
```bash
psql -U postgres -c "CREATE USER studentzkp WITH PASSWORD 'studentzkp';"
psql -U postgres -c "CREATE DATABASE studentzkp OWNER studentzkp;"
psql -h localhost -U studentzkp -d studentzkp -c '\conninfo'   # sanity check
```
Then run the issuer **without** the `local` profile: `cd issuer-backend && ./gradlew bootRun`.

### Native install — macOS / Linux

```bash
# macOS
brew install postgresql@16 && brew services start postgresql@16
# Debian/Ubuntu
sudo apt-get install -y postgresql-16 && sudo systemctl start postgresql

# both
sudo -u postgres psql -c "CREATE USER studentzkp WITH PASSWORD 'studentzkp';"
sudo -u postgres psql -c "CREATE DATABASE studentzkp OWNER studentzkp;"
psql -h localhost -U studentzkp -d studentzkp -c '\conninfo'   # sanity check
```
Then run the issuer **without** the `local` profile: `cd issuer-backend && ./gradlew bootRun`.

## Testing it as intended

The end-to-end flow should convince you of four properties: (a) the issuer's signature is load-bearing, (b) selective disclosure actually hides what it says it hides, (c) disclosures can't be forged, (d) revocation propagates to verifiers without any online check against the issuer. Walk through the scenarios below after a successful happy path.

Keep the issuer and verifier running from the previous section. Every scenario starts by minting a fresh credential and copying the `sdJwt` string.

### 1. Happy path — signature + disclosure hashes valid

1. `bash scripts/demo.sh`, copy the `── SD-JWT-VC (compact) ──` block.
2. Paste into <http://localhost:5173>, click **Verify**.
3. Expect: green "Credential verified" banner; the **Revealed attributes** table contains `is_student: true` and `age_equal_or_over: {"18": true}`; the `Hidden disclosures still bound to this credential` count is nonzero (those are the name hashes, student ID, etc. — present in the credential but not revealed).

### 2. Privacy claim — name + DOB never travel

Decode the JWT payload manually (paste it into <https://jwt.io> offline or use `jq`):

```bash
SDJWT="<paste compact string>"
echo "${SDJWT%%~*}" | awk -F. '{print $2}' \
  | tr '_-' '/+' | base64 -d 2>/dev/null | jq .
```

Expect: the payload contains an `_sd` array of SHA-256 hashes, plus `valid_until` and `status` (always-disclosed). You should **not** see `given_name`, `family_name`, `date_of_birth`, or `student_id` in plaintext anywhere — those live only in the `~disclosure~` segments, and only the ones you choose to pass travel with the credential.

### 3. Tamper detection — flip a byte in the JWT

1. Mint a fresh credential.
2. In the pasted string, change one character in the JWT portion (before the first `~`). Example: the last char of the signature.
3. Click **Verify**.
4. Expect: red "Verification failed" banner, error mentions `signature verification failed` — `jose` rejected the JWS because the signature no longer matches the JWKS public key.

### 4. Tamper detection — forge a disclosure

1. Mint a fresh credential.
2. Decode one disclosure (the segments after `~`): base64url → JSON `[salt, name, value]`. Change `false` → `true` (or similar), re-encode to base64url, splice back into the compact string.
3. Click **Verify**.
4. Expect: red banner with `Disclosure rejected: its hash is not present in the issuer-signed _sd array. The SD-JWT was tampered with or a disclosure was forged.`

### 5. Revocation — issuer revokes, verifier sees it

1. `bash scripts/demo.sh`. Note the printed `Credential ID`.
2. Revoke it:
   ```bash
   curl -X POST http://localhost:8080/dev/credential/<credentialId>/revoke
   ```
3. Re-paste the original SD-JWT-VC into the verifier, click **Verify**.
4. Expect: red "Credential REVOKED" banner. The verifier fetched `/statuslist/uni-2026.json`, inflated the bitstring, and found bit `statusIdx = 1`.
5. **Why this matters:** the verifier made no authenticated request to the issuer — the status list is a public static resource that leaks nothing about *which* credential is being checked.

### 6. Offline / stale status — status list unreachable

This tests the "fail soft on revocation, fail hard on signature" policy (`verify.ts` catches errors from the status list fetch but rethrows everything else). Because JWKS and the status list are both served by the dev issuer today, you need to block just the status list request to test this cleanly:

1. Mint a fresh credential, keep the issuer running.
2. Open the verifier in the browser, open devtools → Network tab.
3. Right-click any request, **Block request URL**, enter `http://localhost:8080/statuslist/uni-2026.json`, confirm.
4. Paste the SD-JWT-VC, click **Verify**.
5. Expect: green "Credential verified" banner with an inline caveat `(status list unreachable — result shown against last-known state)`.

Signature verification still passes (JWKS fetch not blocked), so the verifier trusts the credential but flags its freshness as unknown — exactly what a kiosk with a flaky uplink should do.

### 7. Sanity: the issuer's public key, directly

```bash
curl -s http://localhost:8080/.well-known/jwks.json | jq .
```
Expect: a JWKS with one `EC P-256` key, `use: sig`, `alg: ES256`, and the same `kid` that appears in the JWT header of any credential from step 1.

### What's working vs deferred

| Feature | Status |
|---|---|
| SD-JWT-VC issuance + selective disclosure | ✓ Phase 1 |
| ES256 JWKS endpoint | ✓ Phase 1 |
| IETF Token Status List (deflate + bitstring) | ✓ Phase 1 |
| Browser-side signature + disclosure hash check | ✓ Phase 1 |
| Full OID4VCI pre-authorized-code handshake | Phase 1.5 |
| KB-JWT (holder-bound proof of possession) | Phase 2 |
| QR scan in verifier (`@zxing/library`) | Phase 2 |
| Android holder wallet + StrongBox | Phase 2 |
| BBS+ unlinkable proofs (`crypto-core`) | Phase 3 |
| Play Integrity enforcement | Phase 3 |

### Deviations from `final_plan_md.md`

- **Hand-rolled SD-JWT-VC on nimbus-jose-jwt** instead of walt.id. Same wire format (draft-ietf-oauth-sd-jwt-vc), fewer third-party moving parts, ~150 LOC that's fully auditable in `SdJwtVcService.kt` / `verify.ts`. Swap to walt.id when Phase 1.5 adds real OID4VCI.
- **Postgres-only** (no H2 dev profile). The credential-type-agnostic data model (`final_plan §5.9`) leans on JSONB columns + `gen_random_uuid()`; an H2 compatibility layer was more scope than the one-line `docker run` above.

## Other modules

### crypto-core

```bash
cd crypto-core
cargo check    # Phase 0 — api stubs with todo!() bodies
cargo test
```

### holder-android

See `holder-android/README.md` — generate in Android Studio first.

## Credential types

StudentZK's data model is credential-type-agnostic (`final_plan §5.9`). Phase 0 seeds the `Student` type; admins register additional types (`age/v1`, `library/v1`, `transit/v1`, `event/v1`, …) at runtime. One wallet, many contexts.
