# StudentZK — Technical Documentation

**Project:** StudentZK  
**Date:** May 2026  
**Version:** v2

---

## 1. System Architecture

### Three-party model

The system has three roles: an **issuer** (university backend), a **holder** (student's Android wallet), and a **verifier** (static web app).

```
┌─────────────────────────────────────────────────────┐
│           ISSUER (University Backend)                │
│  Spring Boot · Kotlin · PostgreSQL · Rust crypto     │
│                                                      │
│  OID4VCI Issuer                                      │
│  BBS+ Signing (Rust via JNA)                         │
│  IETF Token Status List Publisher                    │
│  Play Integrity Gateway                              │
└──────────────────────────┬──────────────────────────┘
                           │ OID4VCI credential offer (QR / deep-link)
                           ▼
┌─────────────────────────────────────────────────────┐
│           HOLDER (Android Wallet App)                │
│  Kotlin · Jetpack Compose · StrongBox ES256          │
│                                                      │
│  Stores encrypted credentials                        │
│  Generates BBS+ selective-disclosure proofs          │
│  Signs Key-Binding JWT with hardware-backed key      │
│  Displays QR code for the verifier to scan           │
└──────────────────────────┬──────────────────────────┘
                           │ OID4VP presentation (QR)
                           ▼
┌─────────────────────────────────────────────────────┐
│           VERIFIER (Web App)                         │
│  React · TypeScript · WASM BBS verifier              │
│                                                      │
│  Scans QR via device camera                          │
│  Verifies SD-JWT-VC or BBS-2023 credential           │
│  Checks revocation status list                       │
│  Enforces DCQL claim requirements                    │
│  Runs entirely client-side — zero data collected     │
└─────────────────────────────────────────────────────┘
```

### Credential-type-agnostic data model

The database is not hardcoded to "student". Every credential type is a row in the `credential_type` table. The same issuance pipeline, revocation list, and verifier code work for all types — student IDs, age proofs, library cards, transit passes, event tickets.

```
credential_type  (uri, schema_json, disclosure_policy, default_validity_days)
      │
      └──< credential  (subject_did, attributes_jsonb, cnf_key_jwk, status_idx, revoked)
                │
                └── integrity_assertions  (nonce, verdict_json, verdict_ok)
```

### Attribute disclosure model — Student/v1 credential

| Attribute | Disclosure |
|---|---|
| `student_id` | Always hidden |
| `given_name_hash`, `family_name_hash` | Always hidden (raw names stored only in the issuer DB) |
| `university_id` | Selectively disclosable (holder's choice) |
| `photo_hash` | Selectively disclosable |
| `is_student` | Selectively disclosable |
| `age_equal_or_over.18` | Selectively disclosable |
| `valid_until` | Always disclosed |
| `status` (revocation pointer) | Always disclosed |

### Dual credential format

Every issuance produces two credentials simultaneously:

- **SD-JWT-VC** — the EUDI ARF-mandated format. A signed JWT where each attribute is hidden behind a salted SHA-256 hash. The holder reveals only chosen attributes by including their salts. The issuer's JWS signature is the same across presentations (weak linkability between colluding verifiers, mitigated by batch issuance).
- **W3C VCDM 2.0 + BBS-2023** — the advanced privacy format. Signed with BBS+ on BLS12-381. The holder derives a fresh re-randomised proof for each presentation. Two proofs of the same credential are byte-for-byte different — cryptographically unlinkable even if two verifiers collude and share all their data.

The wallet chooses the format at presentation time; the verifier advertises what it accepts via DCQL.

### Protocol choices

| Layer | Choice | Why |
|---|---|---|
| Issuance | OpenID4VCI 1.0 | EUDI ARF mandate |
| Presentation | OpenID4VP 1.0 over QR with DCQL | EUDI ARF mandate |
| Primary format | SD-JWT-VC (`draft-ietf-oauth-sd-jwt-vc`) | ARF-mandated, fastest prototype path |
| Privacy format | W3C VCDM 2.0 + `bbs-2023` cryptosuite | Cryptographic unlinkability |
| Revocation | IETF Token Status List | Offline-cacheable compressed bitstring |
| Device binding | StrongBox ES256 + `cnf` claim | Hard-crypto theft defense |
| Device attestation | Play Integrity (classic) | Blocks rooted/emulated clients |
| Person binding | Liveness + issuer-signed photo hash | Anti-deepfake layer |

---

## 2. Technologies Used

### Issuer Backend

| Component | Technology | Purpose |
|---|---|---|
| Language | Kotlin 2.1 on JVM 21 | Backend orchestration; code shared with Android |
| Framework | Spring Boot 3.4 | Web server, dependency injection, security |
| Database | PostgreSQL 16 | Credential storage, status list, integrity log |
| Migrations | Flyway | Versioned schema management |
| JOSE / JWT | Nimbus JOSE JWT 10.x | ES256 signing, JWK handling, OID4VCI proof validation |
| BBS+ crypto | Rust `docknetwork/crypto` via JNA | BBS+ signing and proof derivation on BLS12-381 |
| Play Integrity | Google Play Integrity API | Device attestation, rooted/emulated device rejection |
| Embedded DB (dev) | Zonky embedded-postgres 16 | Zero-install local development |

### Cryptographic Core (Rust)

| Component | Technology | Purpose |
|---|---|---|
| BBS+ signatures | `docknetwork/crypto` crate | Sign attribute lists, derive selective-disclosure proofs, verify proofs |
| C-ABI shim | `#[no_mangle] extern "C"` with `catch_unwind` | Safe FFI boundary for JNA (issuer) and UniFFI (Android) |
| Android build | `cargo-ndk` | Cross-compile `.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` |
| JNA binding | `net.java.dev.jna` | Load native `.so` from Kotlin on the JVM without JNI boilerplate |

The Rust library exposes six C-ABI symbols: `studentzkp_bbs_keygen`, `studentzkp_bbs_sign`, `studentzkp_bbs_derive_proof`, `studentzkp_bbs_verify_proof`, `studentzkp_buf_free`, and `studentzkp_last_error`. Each call is wrapped in `catch_unwind` so a Rust panic cannot crash the JVM. The same compiled `.so` is consumed by the Spring Boot issuer (via JNA) and the Android wallet (via JNA, with UniFFI bindings planned).

### Android Holder Wallet

| Component | Technology | Purpose |
|---|---|---|
| Language | Kotlin 2.1 | Native Android development |
| UI | Jetpack Compose + Material 3 | Declarative, modern Android UI |
| Key storage | Android Keystore (StrongBox preferred, TEE fallback) ES256 | Hardware-bound private key — never exported |
| Credential storage | EncryptedSharedPreferences (AES-256) | Encrypted local credential vault |
| QR scanner | CameraX + ML Kit Barcode Scanning | Scan verifier challenges and credential offers |
| QR generator | ZXing 3.5 | Display credential QR codes |
| BBS+ prover | Rust `crypto-core` via JNA | On-device selective-disclosure proof derivation |
| Device attestation | Google Play Integrity API client | Layer 2 anti-abuse |
| Liveness (Phase 3) | MediaPipe Face Detection | Anti-deepfake liveness challenge at enrollment |

### Verifier Web App

| Component | Technology | Purpose |
|---|---|---|
| Framework | React 19 + Vite 6 + TypeScript 5.7 | Static SPA, zero server-side processing |
| QR scanning | `@zxing/library` + `getUserMedia` | Camera-based QR scanning in the browser |
| SD-JWT-VC verify | `jose`, `pako` | Signature check, disclosure hash check, revocation |
| BBS verify | `@mattrglobal/bbs-signatures` WASM | Client-side BBS-2023 proof verification |
| Face match (Phase 3) | `face-api.js` | Live face comparison at verification |

---

## 3. How the Solution Works

### 3.1 Credential Issuance

1. The student opens the wallet app and taps **Add Credential**.
2. The wallet generates an ES256 keypair inside the **Android StrongBox** secure element (TEE fallback on devices without StrongBox). The private key is hardware-bound and can never be exported.
3. The wallet contacts the issuer via **OpenID4VCI** pre-authorized code flow:
   - Scans a QR code containing an `openid-credential-offer://` deep-link.
   - Fetches the credential offer JSON by reference from `GET /credential-offer/{offerId}`.
   - Redeems the pre-authorized code at `POST /token` to receive an `access_token` and a `c_nonce`.
   - Signs a **proof JWT** (type `openid4vci-proof+jwt`) over `{aud, iat, nonce}` using its StrongBox-backed key, embedding the public JWK in the JWT header.
   - POSTs to `POST /credential` with the Bearer token and the proof JWT.
4. The issuer validates the proof JWT: signature against the embedded JWK, correct `aud`, nonce matches `c_nonce`, `iat` within ±5 minutes.
5. The issuer **dual-issues** both formats. Both carry the holder's public JWK as the `cnf` (key confirmation) claim, binding the credential to the holder's hardware key.
6. Both credentials are stored encrypted on the device.

### 3.2 Credential Presentation

1. The student opens the wallet, selects a credential, and taps **Present**.
2. The verifier (or the student manually) provides a `{nonce, audience}` challenge.
3. The wallet builds the presentation:
   - **SD-JWT-VC path:** selects which attribute disclosures to include (default: `is_student` and `age_equal_or_over.18` only). Signs a **Key-Binding JWT** over `{nonce, audience, iat, sd_hash}` with the StrongBox key. Appends it as the final `~` segment.
   - **BBS-2023 path:** calls the Rust `crypto-core` to derive a new re-randomized proof revealing only the selected attributes. The proof is cryptographically distinct from every other proof derived from the same credential.
4. The result is displayed as a QR code. The verifier scans it.

### 3.3 Verification

The verifier web app runs entirely in the browser. No data ever leaves the user's device to a third-party server.

**Step 1 — Format detection:**  
`~` separators in the payload → SD-JWT-VC. JSON object with `@context` and a `proof` field → BBS-2023.

**Step 2 — Signature verification:**  
SD-JWT-VC: fetches issuer JWKS from `GET /.well-known/jwks.json` (HTTP-cacheable), verifies the ES256 JWS.  
BBS-2023: fetches the issuer's BLS12-381 public key from `GET /.well-known/studentzkp-bbs-key.json`, verifies the BBS proof via WASM.

**Step 3 — Disclosure hash check (SD-JWT-VC):**  
For each revealed disclosure `[salt, name, value]`, recomputes `SHA-256(base64url([salt, name, value]))` and confirms it appears in the `_sd` array of the signed JWT. Any forged or tampered disclosure fails.

**Step 4 — Key-Binding JWT check (SD-JWT-VC):**  
Validates the trailing KB-JWT: `typ=kb+jwt`, ES256 signature against `cnf.jwk`, `sd_hash` matches the disclosed set, nonce matches the challenge, `iat` within ±5 minutes. Proves the credential is being presented from the original hardware key.

**Step 5 — Revocation check:**  
Fetches the IETF Token Status List from `GET /statuslist/uni-2026.json`. Decodes the deflate-compressed bitstring. Checks the bit at index `status.status_list.idx`. Bit `0` = valid, bit `1` = revoked. Public static resource — no authenticated call, no privacy leakage.

**Step 6 — DCQL enforcement:**  
Checks that credential format, credential type (`vct`), and all required claims match the verifier's declared DCQL requirements.

**Step 7 — Result:**  
Green "Credential verified" banner with disclosed attributes. Red banner with specific failure reason otherwise.

### 3.4 Offline Operation

| Step | Needs network? |
|---|---|
| Enrollment (issuance) | Yes — OID4VCI protocol, Play Integrity |
| Credential storage | No — encrypted on device |
| **Presentation (QR generation at bar/train/museum)** | **No — all crypto runs on-device** |
| Verifier signature check | Cached-online — JWKS cached in browser |
| Revocation check | Cached-online — status list cacheable via CDN, freshness badge shown if stale |
| Live face-match (Phase 3) | Yes — must fetch signed photo URL |

Presentation cryptography is fully offline. The verifier works against cached trust material and shows a freshness badge when caches are stale. Live face-match and Play Integrity are the two paths that require connectivity — acknowledged clearly.

### 3.5 Revocation

Each credential is assigned a unique index in a 131,072-bit IETF Token Status List bitstring, published at `/statuslist/uni-2026.json`. The issuer sets the bit on revocation. Any verifier anywhere fetches this list (cacheable, CDN-deployable, ~20 KB for 100,000 credentials) and checks one bit — without any authenticated call or revealing which credential is being checked.

### 3.6 Multiple Credential Types

| Type URI | Display name | Selectively disclosable attributes |
|---|---|---|
| `studentzk.eu/types/student/v1` | University student | `is_student`, `age_equal_or_over.18`, `university_id`, `photo_hash` |
| `studentzk.eu/types/age/v1` | Age proof | `age_equal_or_over.18` only — no PII |
| `studentzk.eu/types/library/v1` *(planned)* | Library member | `is_member`, `library_id`, `tier` |
| `studentzk.eu/types/transit/v1` *(planned)* | Youth transit pass | `is_eligible_youth_fare`, `operator_id` |
| `studentzk.eu/types/event/v1` *(planned)* | Event attendee | `event_id`, `tier` |

Registering a new type is a single database row insert with a JSON Schema and a disclosure policy. No code changes required anywhere in the stack.

---

## 4. Security Model

### Threat overview

| # | Attack | Goal | Defense |
|---|---|---|---|
| A1 | Credential theft — copy the file off the phone | Present someone else's credential | Layer 1: private key hardware-bound, cannot be exported |
| A2 | Replay — record a valid QR and reuse it | Multiple discounts from one issuance | Layer 1: KB-JWT nonce + `iat` window |
| A3 | Emulator / rooted device — fake wallet posture | Create synthetic holders, mass fraud | Layer 2: Play Integrity verdict checked server-side |
| A4 | Deepfake selfie at issuance | Genuine credential on a fake identity | Layer 3: liveness challenge at enrollment |
| A5 | Real-time deepfake at verification | Bypass live face check | Defense in depth: must defeat all three layers simultaneously |
| A6 | Photo-swap — valid credential, wrong face shown | Evade the face check | Layer 3: verifier recomputes `SHA-256(photo)` vs `photo_hash` in credential |
| A7 | Social engineering — holder shares willingly | Share a student account | User consent screen; unwinnable at the cryptographic layer |

### Layer 1 — Device Binding

Every credential carries a `cnf` (key confirmation) claim containing the holder's public JWK. The private key was generated in and never leaves the Android **StrongBox** secure element. At presentation the holder must sign a Key-Binding JWT using that key. A stolen credential on another phone is cryptographically useless — it cannot produce a valid KB-JWT.

### Layer 2 — Device Attestation

The Android wallet calls **Google Play Integrity API** (classic mode with a server-issued nonce). The resulting token is bound to `SHA-256(KB-JWT payload)` and decoded **server-side** — the client cannot forge a verdict. The issuer checks:
- `MEETS_DEVICE_INTEGRITY` + `MEETS_STRONG_INTEGRITY` — blocks rooted devices and custom ROMs
- `PLAY_RECOGNIZED` — confirms the app is the genuine Play Store build, not tampered
- `NO_ISSUES` — no Play Protect warnings

The nonce is single-use (database unique constraint) — replay of a previous token is detected and rejected.

### Layer 3 — Person Binding (Phase 3)

At enrollment the holder captures a **liveness-checked selfie** (MediaPipe: random head-turn + blink + color flash). The issuer stores `SHA-256(lowResPhoto)` as `photo_hash` inside the credential. The photo lives in object storage behind a short-lived (60-second TTL) signed URL bound to the presentation nonce.

At verification: the verifier fetches the photo, recomputes the hash (kills A6), and runs `face-api.js` live comparison against the camera feed.

Against real-time deepfake (A5): the attacker must also defeat Layer 1 (needs the victim's hardware key) and Layer 2 (needs a genuine Play-certified non-rooted device). Defeating all three simultaneously is materially harder than any single legacy KYC system.

### GDPR

- Raw names and dates of birth are stored only in the issuer's PostgreSQL database, which is already the university's authentic data source.
- Credentials contain only SHA-256 hashes of names — never plaintext.
- The verifier web app is fully client-side and retains no data.
- Photos are stored encrypted, served via single-use signed URLs, and never enter a biometric matcher database. This avoids GDPR Article 9 special-category biometric processing.

---

## 5. Monorepo Structure

```
student-zkp/
├── issuer-backend/                       Kotlin Spring Boot issuer
│   ├── build.gradle.kts
│   └── src/main/kotlin/hr/fer/studentzkp/
│       ├── config/                       Spring config, security, embedded Postgres
│       ├── controller/                   HTTP endpoints (OID4VCI, integrity, dev)
│       ├── service/                      Business logic (issuance, BBS, status list)
│       ├── crypto/                       JNA BBS bridge
│       └── repository/                   JPA credential and student repositories
├── verifier-web/                         React TypeScript verifier SPA
│   └── src/
│       ├── lib/verify.ts                 All verification logic
│       └── App.tsx                       UI and DCQL definition
├── holder-android/StudentZK/            Android Kotlin wallet
│   └── app/src/main/java/hr/fer/studentzkp/holder/
│       ├── ui/                           Jetpack Compose screens
│       ├── wallet/                       Credential storage and OID4VCI client
│       └── crypto/                       BBS bridge
├── crypto-core/                          Rust BBS+ shared library
│   ├── src/lib.rs                        BBS+ sign / derive_proof / verify_proof
│   └── src/ffi.rs                        C-ABI shim
├── scripts/
│   ├── demo.sh / demo.ps1               End-to-end demo
│   └── build-crypto.sh / .ps1           Rust cross-compile
└── docs/
    ├── api.md                            Full HTTP API reference
    ├── architecture.md                   Architecture diagram
    └── threat-model.md                   Attacker model details
```

### Key files

| File | Purpose |
|---|---|
| `issuer-backend/src/.../service/SdJwtVcService.kt` | SD-JWT-VC issuance |
| `issuer-backend/src/.../service/BbsVcService.kt` | BBS-2023 credential issuance |
| `issuer-backend/src/.../service/StatusListService.kt` | IETF Token Status List generation |
| `issuer-backend/src/.../crypto/BbsCryptoBridge.kt` | JNA binding to Rust crypto |
| `issuer-backend/src/.../service/PlayIntegrityService.kt` | Play Integrity verdict decoding |
| `verifier-web/src/lib/verify.ts` | All client-side verification logic |
| `crypto-core/src/lib.rs` | BBS+ sign, derive, verify (Rust) |
| `crypto-core/src/ffi.rs` | C-ABI shim |
| `holder-android/.../wallet/HolderKeyManager.kt` | StrongBox ES256 key generation |

---

## 6. Glossary

| Term | Meaning |
|---|---|
| **BBS+ / BBS-2023** | A cryptographic signature scheme on BLS12-381 that enables selective disclosure and produces a fresh unlinkable proof on every presentation |
| **SD-JWT-VC** | Selective-Disclosure JWT Verifiable Credential — a signed JWT where each attribute is hidden behind a salted SHA-256 hash |
| **OID4VCI** | OpenID for Verifiable Credential Issuance — standard protocol for a wallet to receive a credential from an issuer |
| **OID4VP** | OpenID for Verifiable Presentations — standard protocol for a wallet to present a credential to a verifier |
| **DCQL** | Digital Credentials Query Language — the verifier's declaration of which credential types and claims it requires |
| **IETF Token Status List** | A deflate-compressed bitstring at a public URL; one bit per issued credential for revocation |
| **KB-JWT** | Key-Binding JWT — a short-lived JWS proving the holder controls the hardware key bound into the credential |
| **StrongBox** | A dedicated hardware security module chip on supported Android devices; the private key never leaves it |
| **cnf claim** | JSON "key confirmation" claim binding a credential to a specific public key |
| **eIDAS 2.0** | EU Regulation 2024/1183 requiring EU member states to provide certified digital identity wallets by end of 2026 |
| **EUDI ARF** | EU Digital Identity Architecture Reference Framework — technical specification for EUDI wallet interoperability |
| **Play Integrity** | Google's device attestation API providing hardware-backed signals about device health and app authenticity |
| **Unlinkability** | The property that multiple presentations of the same credential cannot be correlated — even by an attacker with all cryptographic material from both presentations |
| **JMBAG** | Jedinstveni matični broj akademskog građana — the 10-digit unique student identifier used by Croatian universities |
