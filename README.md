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

- JDK 21 (Temurin)
- Node 20+
- Rust 1.75+ (`rustup target add aarch64-linux-android x86_64-linux-android`)
- PostgreSQL 16+
- Android Studio + NDK r27
- Google Cloud project with Play Integrity API enabled (Phase 1+)

## Phase 1 demo — issuer → verifier end-to-end

Phase 1 ships the SD-JWT-VC happy path: the issuer mints a credential for a seeded student, the verifier-web app takes the compact string, fetches JWKS + status list from the issuer, and displays the selectively-disclosed claims — all client-side in the browser.

Three terminals, one flow.

**1. Postgres.** Flyway owns the schema; you just need an empty DB named `studentzkp` owned by a user `studentzkp` with password `studentzkp` (matches `application.yml` defaults — override with `DB_USERNAME` / `DB_PASSWORD` env vars if you prefer). Pick one of the three paths below.

<details><summary><b>Option A — Docker (one command)</b></summary>

```bash
docker run -d --name studentzkp-pg \
  -e POSTGRES_DB=studentzkp -e POSTGRES_USER=studentzkp -e POSTGRES_PASSWORD=studentzkp \
  -p 5432:5432 postgres:16
```
</details>

<details><summary><b>Option B — Native install on Windows</b></summary>

Install Postgres 16 via winget (opens as admin):
```powershell
winget install PostgreSQL.PostgreSQL.16
```
Then from a shell (`psql` is in `C:\Program Files\PostgreSQL\16\bin\`; add to PATH or use the full path):
```bash
psql -U postgres -c "CREATE USER studentzkp WITH PASSWORD 'studentzkp';"
psql -U postgres -c "CREATE DATABASE studentzkp OWNER studentzkp;"
```
Alternative: the EDB graphical installer at <https://www.postgresql.org/download/windows/> — pick the same values in the wizard and skip Stack Builder at the end.
</details>

<details><summary><b>Option C — Native install on macOS / Linux</b></summary>

```bash
# macOS
brew install postgresql@16 && brew services start postgresql@16
# Debian/Ubuntu
sudo apt-get install -y postgresql-16 && sudo systemctl start postgresql

# both
sudo -u postgres psql -c "CREATE USER studentzkp WITH PASSWORD 'studentzkp';"
sudo -u postgres psql -c "CREATE DATABASE studentzkp OWNER studentzkp;"
```
</details>

Sanity check (any option): `psql -h localhost -U studentzkp -d studentzkp -c '\conninfo'` should connect. Flyway runs migrations on the issuer's first boot — no schema setup needed.

**2. Issuer.** Generates an ES256 signing key at boot, serves `/.well-known/jwks.json` + `/statuslist/uni-2026.json`, and exposes a dev-only `/dev/credential/{studentId}` shortcut.
```bash
cd issuer-backend
./gradlew bootRun
```

**3. Verifier.** Static SPA — the issuer URL is the only config.
```bash
cd verifier-web
cp .env.example .env.local        # VITE_ISSUER_BASE_URL=http://localhost:8080
npm install
npm run dev
```

**4. Run the demo.** Mints Ana Anić's credential and prints the SD-JWT-VC plus human-readable disclosures.
```bash
bash scripts/demo.sh
```

Copy the printed SD-JWT-VC, open <http://localhost:5173>, paste it in, and click **Verify**. You'll see `is_student=true` and `age_equal_or_over.18=true`; the name/DOB/student-number hashes stay bound to the credential but never leave your browser.

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
