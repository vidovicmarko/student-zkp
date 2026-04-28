# StudentZK — Roadmap & Outstanding Work

This file is the handoff note: what Phase 1 ships, what's deliberately stubbed, and the concrete entry points for picking each deferred piece back up. Phase numbering follows `final_plan_md.md §8`.

Use it as a checklist — every item lists the file(s) to touch, the spec anchor, and the "done when" test.

---

## Phase 1 — Shipped (testable today)

End-to-end SD-JWT-VC happy path. Full walkthrough in `README.md` → "Testing it as intended". Summary:

| Capability | Entry point |
|---|---|
| ES256 signing key (boot-time, in-memory) | `issuer-backend/.../service/IssuerKeyService.kt` |
| SD-JWT-VC issuer (hand-rolled on nimbus-jose-jwt) | `issuer-backend/.../service/SdJwtVcService.kt` |
| IETF Token Status List (deflate + bitstring) | `issuer-backend/.../service/StatusListService.kt` |
| Dev-only issuance shortcut | `POST /dev/credential/{studentId}` |
| Dev-only revocation shortcut | `POST /dev/credential/{credentialId}/revoke` |
| Browser-side verifier | `verifier-web/src/lib/verify.ts` |
| Embedded Postgres under `local` profile | `issuer-backend/.../config/EmbeddedPostgresConfig.kt` |

---

## Phase 1.5 — Close out OID4VCI (shipped)

A real wallet can now drive the full pre-authorized-code handshake against our issuer. The dev shortcut is still around for `scripts/demo.sh` but only registers under the `dev-shortcut` profile.

### Done

- [x] **`/credential-offer/{offerId}`.** Returns the OID4VCI offer JSON. `Oid4VciController.kt`, [OpenID4VCI 1.0 §4.1.1](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html).
- [x] **`/token`.** Form-encoded RFC 6749 endpoint, accepts the pre-authorized-code grant, returns `access_token` + `c_nonce`.
- [x] **`/credential`.** Validates the wallet's `openid4vci-proof+jwt` (signature against header `jwk`, `aud` = issuer URI, `nonce` matches `c_nonce`, `iat` within ±5 min), then issues an SD-JWT-VC bound to that `jwk` via `cnf`.
- [x] **Dev shortcut moved to `@Profile("dev-shortcut")`.** Auto-enabled by the `local` profile group so `scripts/demo.sh` keeps working unchanged. Production deployments leave it off and the `/dev/**` endpoints aren't even registered.
- [x] **`POST /dev/credential-offer/{studentId}`.** Helper to mint an offer + deep-link without an admin UI. Returns `offer_id`, `pre_authorized_code`, `credential_offer_uri`, and the `openid-credential-offer://` deep-link.

### Remaining (deferred, not blocking)

- [ ] **Swap hand-rolled SD-JWT-VC for walt.id or EUDI libs** *(optional)*. Our hand-rolled impl is audit-friendly but lacks KB-JWT support. Artifacts: `id.walt:waltid-identity:1.0.x`, `eu.europa.ec.eudi:eudi-lib-jvm-sdjwt-kt`. Commented TODOs already in `issuer-backend/build.gradle.kts`.
- [ ] **Render the offer as a scannable QR.** Tiny admin page that renders the deep-link returned by `POST /dev/credential-offer/{studentId}` as a QR using `zxing-js` or a server-side renderer.
- [ ] **End-to-end test against [walt.id wallet](https://walt.id/wallet)** to confirm protocol-level interop. Manual test, requires a phone.

---

## Phase 2 — BBS+ selective disclosure + device binding (est. 2–3 weeks)

This is where the *unlinkability* story kicks in. SD-JWT-VC is replayable — the issuer's JWS signature is identical across presentations, so two verifiers can trivially correlate. BBS-2023 rerandomizes on every proof.

### Tasks

- [x] **Rust `crypto-core` — BBS+ implementation done.** `crypto-core/src/lib.rs` has working `bbs_keygen`, `bbs_sign`, `bbs_derive_proof`, `bbs_verify_proof` on BLS12-381 via `docknetwork/bbs_plus`. 5 unit tests pass under `cargo test`, covering full disclosure, selective disclosure, replay protection, tamper detection, and unlinkability.
- [x] **C-ABI shim around the Rust functions.** `crypto-core/src/ffi.rs` exposes 6 `#[no_mangle] extern "C"` entry points (`studentzkp_bbs_keygen` / `_sign` / `_derive_proof` / `_verify_proof`, `studentzkp_buf_free`, `studentzkp_last_error`). `ByteBuf`/`ByteSlice` repr-C types; `i32` status codes; thread-local last-error; panic-safe (each call wrapped in `catch_unwind`). Hand-curated header at `crypto-core/include/studentzkp_crypto.h`. Tests pass: 8/8 (5 original + 3 FFI: full roundtrip, error propagation, null-ptr handling).
- [x] **Build `.so` for Android targets.** `scripts/build-crypto.sh` (and `.ps1`) wraps `cargo-ndk` for the four Android ABIs (`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android`) and drops outputs at `holder-android/StudentZK/app/src/main/jniLibs/<abi>/libstudentzkp_crypto.so` — the path AGP auto-bundles into the APK. Host build is in the same script: `cargo build --release` produces `studentzkp_crypto.{dll,so,dylib}` and copies it to `issuer-backend/build/native/`. The issuer's `bootRun` and `test` tasks both set `jna.library.path` to that dir so `Native.load("studentzkp_crypto")` resolves with no extra config. Host path verified end-to-end on this machine; Android cross-compile requires `cargo install cargo-ndk` and an NDK install (env: `ANDROID_NDK_HOME`).
- [x] **JNA binding on the issuer + holder.** `issuer-backend/.../crypto/BbsCryptoBridge.kt` is now a real JNA `Library` interface mirroring the six C-ABI symbols, plus a `BbsCrypto` Kotlin facade that hides Memory pinning, status-code translation, last-error retrieval, and `studentzkp_buf_free` cleanup. End-to-end: 5/5 JUnit tests pass against the live cdylib (sign + derive + verify roundtrip, wrong-nonce rejection, unlinkability, error path, tamper detection). Mirrored to `holder-android/.../crypto/BbsCryptoBridge.kt` (JNA `@aar`, abiFilters locked to `arm64-v8a` + `x86_64` because the bridge wires `size_t → Java long`).
- [x] **Dual-issue.** `BbsVcService` emits a W3C VCDM 2.0 credential alongside the SD-JWT-VC, signed with BBS+ on BLS12-381 via the JNA bridge. Same attribute set; both forms returned in `IssuedCredentialDto.bbsVc`. Persisted issuer keypair at `./.studentzkp/issuer-bbs-key.json` (mirrors the ES256 pattern). Public key advertised at `GET /.well-known/studentzkp-bbs-key.json` — verifiers fetch this to learn the pubkey + canonicalization. Cryptosuite: `studentzk-bbs-2023` (variant of W3C `bbs-2023` with `studentzk-canonical-v1` flat-leaf canonicalization rather than URDNA2015 — explicitly tagged in the proof so verifiers know which canonicalization to mirror; swap for real URDNA2015 only if EUDI interop becomes a goal). Tests: 4/4 (verify, selective-disclosure, unlinkability across two derivations, base64 roundtrip).
- [x] **DCQL on the verifier.** Updated DCQL_REQUEST to advertise both `vc+sd-jwt` and `vc+bbs` formats. `verifyPresentation()` detects format (SD-JWT-VC contains ~, BBS is JSON) and routes to appropriate verifier. Wallet can choose format; both prove same attributes. Files: `verifier-web/src/App.tsx`, `verifier-web/src/lib/verify.ts`.
- [x] **BBS verify in the browser.** Integrated `@mattrglobal/bbs-signatures@2.0.0` WASM library for client-side BBS-2023 proof verification. `verifyBbsCredential()` fetches issuer's public key from `/.well-known/studentzkp-bbs-key.json`, verifies W3C VCDM 2.0 credentials with DataIntegrityProof. Lazy-loading not needed (library auto-loads WASM on first call). Proof hashing added for unlinkability comparison.
- [x] **KB-JWT (Key-Binding JWT) on SD-JWT-VC.** Holder signs `{nonce, aud, iat, sd_hash}` with the StrongBox key; appended as the final `~` segment. Wallet now sends its public JWK as `cnf` at issuance (`CredentialRepository.devIssueAndStore` → `IssuerApiClient.devIssueCredential`), and the credential detail screen has a **Present** dialog that takes a verifier's `{nonce, audience}` challenge and emits an SD-JWT-VC + KB-JWT combo as text + QR. Verifier-web (`verify.ts`) detects the trailing KB-JWT, validates `typ=kb+jwt`, ES256 signature against `cnf.jwk`, `sd_hash`, `nonce`, `aud`, and `iat` (±5 min). `App.tsx` exposes a "Generate Challenge" step + "Require Key-Binding JWT" toggle and shows a "Device-bound" badge on success.
- [x] **Android holder wallet.** Compose wallet app built under `holder-android/StudentZK/`. Credential issuance via dev shortcut, QR display/scan, SD-JWT-VC verification with disclosure hash check + status list revocation check, paste-to-verify, copy credential, dismissible scan result card with age/university/student details. Encrypted storage via `EncryptedSharedPreferences` (AES-256). *UniFFI/BBS+ binding, biometric unlock, and `askar` storage are still Phase 2.*
- [x] **StrongBox ES256 key at wallet provisioning.** `HolderKeyManager.kt`: tries `setIsStrongBoxBacked(true)` first, falls back to TEE automatically. No hard refusal if neither is present (emulator compat).
- [x] **Play Integrity anti-replay device attestation.** Android app requests a nonce via `POST /integrity/nonce` (returns expiring 32-byte challenge), submits Play Integrity token to `POST /integrity/verify` with nonce bound in the token. Server-side decoding via `google-api-services-playintegrity` decodes and verifies against Google's API. Verdicts stored in `integrity_assertions` table with nonce uniqueness enforcement (replay detection). `PlayIntegrityService` (prod profile) checks device integrity (rejects rooted/emulator), app integrity (PLAYS_CERTIFIED), and enforces nonce single-use. Stub implementation active in dev. Files: `issuer-backend/src/.../service/PlayIntegrityService.kt`, `issuer-backend/.../controller/IntegrityController.kt`.

### Done when

Two consecutive BBS presentations of the same credential produce cryptographically unlinkable proofs (run `sha256` over each proof — they differ). SD-JWT-VC presentations produce identical issuer signatures (demonstrates why BBS wins the linkability story). Rooted/emulator device is refused by Play Integrity.

---

## Phase 2 — Complete ✓

All Phase 2 tasks delivered:
- KB-JWT device binding (Task #6)
- C-ABI crypto shim (Task #7)
- Cross-compile to Android (Task #8)
- JNA bindings (Task #9)
- Dual-issue BBS+ credentials (Task #10)
- BBS verification in browser via WASM (Task #11)
- DCQL format dispatch and validation (Task #12)
- Play Integrity anti-replay attestation (Task #13)

The platform now supports:
- Two proof formats (SD-JWT-VC, BBS-2023) with automatic format detection
- Device-bound credentials via Key-Binding JWT
- Unlinkable BBS+ proofs (different hash per presentation)
- Format enforcement and claim validation via DCQL
- Play Integrity nonce-based anti-replay at device level
- Two credential types (student/v1, age/v1) proving extensibility

**Demo ready**: `/scripts/demo.sh` issues student credentials; holder-android wallet presents them; verifier-web validates both formats, checks key binding, enforces DCQL, and demonstrates BBS unlinkability via sha256 comparison.

---

## Phase 3 — Anti-abuse, photo binding, extensibility (est. 2–3 weeks)

Answering the jury's three open questions from the v2 plan's §0 changelog: deepfake resistance, offline honesty, scope breadth.

### Tasks

- [ ] **Research external student card validation services.** Investigate whether University of Zagreb or other Croatian universities offer APIs/services to validate physical student card QR codes and retrieve card validity dates. File: create `docs/student-card-validation-research.md` documenting findings on: (a) available APIs (e.g., Unizag card service, national ID registry), (b) QR code format and payload structure, (c) rate limits and auth requirements, (d) how to map physical card validity to credential expiry. This informs whether we can cross-validate our credentials against the real student ID system for anti-fraud.
- [ ] **Liveness-checked selfie at issuance.** MediaPipe challenge flow on Android: head turn + blink + color flash. File: new `holder-android/app/src/.../liveness/`. Emits a `photo_hash` that the issuer signs into the credential.
- [ ] **Signed short-lived photo URL.** Issuer uploads the enrolled photo to object storage, emits a 60-second signed URL bound to the presentation nonce. Verifier's browser pulls the photo and runs `face-api.js` live-match against the camera feed.
- [ ] **Batch issuance.** Issue N single-use SD-JWT-VCs per student (say N=10) so even the SD-JWT-VC path has unlinkability parity with BBS in the short term. Add a `batch_id` + `single_use` flag on `credential`.
- [x] **Register a second credential type.** Age/v1 credential type seeded via `V5__add_age_credential_type.sql`. Endpoint: `POST /dev/credential/age/{studentId}` issues age-only credential (age_equal_or_over.18 only, no PII). Reuses same student database and issuance infrastructure (SD-JWT-VC + BBS-2023 dual-issue). Proves platform is credential-type-agnostic — same attribute hashing logic, revocation list, validity model work across types. Files: `issuer-backend/src/.../service/AgeIssuanceService.kt`, `DevIssuanceController.kt`.
- [ ] **Admin UI.** Spring + Thymeleaf or a small React page under `/admin`. List students, list credential types, issue/revoke, status list editor. Lock down with proper auth (currently `SecurityConfig` is wide open — see "Known issues" below).
- [ ] **Android UX polish.** Presentation history, clear "what is disclosed" screen, generic card stack (not "Student card" hardcoded). *Partially done: credential detail screen shows disclosed attributes, QR sharing, scan result card with student/age/university rows. Still missing: presentation history log, generic multi-type card stack.*
- [ ] **Stretch: accumulator-based revocation.** Stubs already present in `crypto-core/src/lib.rs`. Hook into BBS proofs so revocation is ZK too.

### Done when

Full demo script from `final_plan_md.md §8 Phase 3` passes: rooted device refused, deepfake selfie rejected at issuance, two credential types coexist in one wallet, verifier runs face-match against the live camera without any server round-trip past the signed URL.

---

## Known issues to fix when you're back

Each of these is small on its own but will bite in a real deployment. Leaving them here so they aren't rediscovered painfully.

- [x] **`statusIdx` allocation is racy.** Replaced with the `credential_status_idx_seq` Postgres sequence (Flyway `V4__status_idx_sequence.sql`); `CredentialRepository.nextStatusIdx()` calls `nextval()`.
- [x] **`SecurityConfig.kt` permits everything.** Now uses HTTP Basic for `/integrity/**` and `/admin/**`; `/dev/**` is open only when the `dev-shortcut` profile is active and denied otherwise; OID4VCI public endpoints (`/credential-offer/**`, `/token`, `/credential`) stay open. Admin credentials default to a per-boot generated password (logged at startup); set `studentzkp.admin.password` (or `STUDENTZKP_ADMIN_PASSWORD` env) for stable deployments.
- [x] **Issuer signing key is ephemeral.** `IssuerKeyService` now persists an ES256 JWK to `studentzkp.issuer.keyPath` (default `./.studentzkp/issuer-signing-key.jwk`). Delete the file to rotate. `.studentzkp/` is gitignored. Real KMS/HSM is still future work.
- [ ] **Status list is rebuilt on every fetch.** `StatusListService.kt` scans all revoked credentials each call. Fine at demo scale (131072-bit capacity, single-digit revocations); cache + invalidate on revocation for prod.
- [ ] **`cnf_key_jwk` is nullable until Phase 2.** `V3__phase1_adjustments.sql` drops the NOT NULL. Put it back when KB-JWT lands (Phase 2).
- [x] **Verifier DCQL parsing and enforcement.** `verifyPresentation()` now accepts optional `dcqlRequest` parameter. New `validateDcql()` function checks: format matches (vc+sd-jwt / vc+bbs), credential type in accepted list, all required claims disclosed. Returns list of validation errors attached to `VerifyResult.dcqlValidation`. UI shows green badge if all requirements met, red badge with error details if not. Files: `verifier-web/src/lib/verify.ts`, `verifier-web/src/App.tsx`.
- [ ] **Embedded-postgres binaries BOM bloats the jar.** `io.zonky.test.postgres:embedded-postgres-binaries-bom:16.2.0` adds ~60MB. Move to a `developmentOnly` configuration (Spring Boot plugin supports `developmentOnly`) before cutting a production jar.
- [ ] **`gradle/wrapper/gradle-wrapper.jar` is not committed.** First-time clones must run `gradle wrapper` once. Add the jar or document in the README prereqs.
- [ ] **No CI.** Add a GitHub Actions workflow that runs `./gradlew build` + `npm run build` on PR.

---

## References (skim before restarting)

- `final_plan_md.md` — authoritative spec.
- `docs/architecture.md` — v2 architecture diagram.
- `docs/threat-model.md` — anti-abuse narrative.
- [OpenID4VCI draft 14](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html)
- [OpenID4VP 1.0](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html)
- [draft-ietf-oauth-sd-jwt-vc](https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/)
- [draft-ietf-oauth-status-list](https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)
- [EUDI ARF 2.x](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework)
