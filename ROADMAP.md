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
- [ ] **C-ABI shim around the Rust functions.** `bbs_plus` returns idiomatic Rust types; JNA + UniFFI both need `#[no_mangle] extern "C"` wrappers with raw pointers + lengths and a free function. Last mile before the JVM and Android can call into `crypto-core`.
- [ ] **Build `.so` for Android targets.** `aarch64-linux-android`, `x86_64-linux-android`, plus host JVM arch via `cargo-ndk` or the `rust-android-gradle` plugin.
- [ ] **JNA binding on the issuer.** `BbsCryptoBridge.kt` declares the interface; once the C-ABI shim lands, point `Native.load(...)` at the built `.so` and replace the placeholder method shapes with real `extern "C"` signatures.
- [ ] **Dual-issue.** Next to the SD-JWT-VC, emit a W3C VCDM 2.0 credential with `bbs-2023` cryptosuite. Schema: `valid_until`, `status`, `is_student`, `age_equal_or_over.18`, name hashes (same attributes, different envelope).
- [ ] **DCQL on the verifier.** Parse the DCQL request from `App.tsx` properly; advertise both `vc+sd-jwt` and `vc+bbs` as accepted formats; let the wallet pick.
- [ ] **BBS verify in the browser.** WASM build of `docknetwork/crypto` or `@mattrglobal/bbs-signatures`. ~1MB, lazy-loaded behind a dynamic import so the SD-JWT-VC path stays fast.
- [ ] **KB-JWT (Key-Binding JWT) on SD-JWT-VC.** Holder signs `{nonce, aud, sd_hash}` with the StrongBox key; appended as the final `~` segment. Verifier checks `cnf` JWK in the credential matches the kb_jwt signing key. File: extend `verifier-web/src/lib/verify.ts` to validate the trailing segment if present.
- [ ] **Android holder wallet.** Generate in Android Studio (Compose, `minSdk 31` for StrongBox). Wire UniFFI binding to `crypto-core`. Use `askar` (`@hyperledger/aries-askar`) for encrypted storage. Biometric unlock before proof generation.
- [ ] **StrongBox ES256 key at wallet provisioning.** `KeyGenParameterSpec.Builder(...).setIsStrongBoxBacked(true)`. Fall back to TEE if StrongBox unavailable; refuse to provision if neither is present.
- [ ] **Play Integrity — classic request at issuance, standard request at presentation.** Server-side verdict decoding via `google-api-services-playintegrity` (already in the issuer `build.gradle.kts`). Write verdicts to `integrity_assertions` table (schema already exists, `V1__init_schema.sql`).

### Done when

Two consecutive BBS presentations of the same credential produce cryptographically unlinkable proofs (run `sha256` over each proof — they differ). SD-JWT-VC presentations produce identical issuer signatures (demonstrates why BBS wins the linkability story). Rooted/emulator device is refused by Play Integrity.

---

## Phase 3 — Anti-abuse, photo binding, extensibility (est. 2–3 weeks)

Answering the jury's three open questions from the v2 plan's §0 changelog: deepfake resistance, offline honesty, scope breadth.

### Tasks

- [ ] **Liveness-checked selfie at issuance.** MediaPipe challenge flow on Android: head turn + blink + color flash. File: new `holder-android/app/src/.../liveness/`. Emits a `photo_hash` that the issuer signs into the credential.
- [ ] **Signed short-lived photo URL.** Issuer uploads the enrolled photo to object storage, emits a 60-second signed URL bound to the presentation nonce. Verifier's browser pulls the photo and runs `face-api.js` live-match against the camera feed.
- [ ] **Batch issuance.** Issue N single-use SD-JWT-VCs per student (say N=10) so even the SD-JWT-VC path has unlinkability parity with BBS in the short term. Add a `batch_id` + `single_use` flag on `credential`.
- [ ] **Register a second credential type.** Seed `age/v1` or `library/v1` through the existing `credential_type` registry — prove the platform is credential-type-agnostic. This directly answers "limited scope" from the jury.
- [ ] **Admin UI.** Spring + Thymeleaf or a small React page under `/admin`. List students, list credential types, issue/revoke, status list editor. Lock down with proper auth (currently `SecurityConfig` is wide open — see "Known issues" below).
- [ ] **Android UX polish.** Presentation history, clear "what is disclosed" screen, generic card stack (not "Student card" hardcoded).
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
- [ ] **Verifier lacks proper DCQL parsing.** `App.tsx` declares a hardcoded DCQL object but doesn't actually enforce it against the credential. Needs a DCQL matcher against `payload.vct` + disclosure names.
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
