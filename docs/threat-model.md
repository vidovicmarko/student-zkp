# StudentZK Threat Model

Derived from `final_plan_md.md §5.8`. No single defense is sufficient against a motivated attacker; three independent layers together are.

## Attacks in scope

| # | Attack | Goal | Attacker effort |
|---|---|---|---|
| A1 | **Credential theft** — copy credential file from victim's phone | Present another person's "is_student" | Trivial without device binding |
| A2 | **Replay** — record a valid QR, replay at different verifier | Multiple discounts from one issuance | Low |
| A3 | **Emulator / rooted device** — modified wallet lies about posture | Create synthetic holders; mass fraud | Medium |
| A4 | **Deepfake selfie at issuance** — synthetic face + forged ID doc | Genuine credential bound to fake identity | Medium-high |
| A5 | **Deepfake selfie at verification** — real-time avatar of victim | Bypass live-face-vs-photo check | High (rising 2024–2026) |
| A6 | **Photo-swap at verification** — valid credential, wrong selfie | Avoid detection if verifier is sloppy | Low |
| A7 | **Social engineering** — holder shares credential voluntarily | Share a student account | Trivial |

## Defensive layers

### Layer 1 — Device binding (defends A1, A2)

Every credential carries a `cnf` claim tied to an ES256 key generated in **Android StrongBox** (TEE fallback). The private key never leaves hardware. At presentation the holder signs a Key-Binding JWT over `{nonce, audience, iat}` with that key. A cloned credential on another device cannot sign the KB-JWT — it is useless. Nonce + `iat`/`exp` block replay.

**This is hard cryptography. No deepfake beats it.**

### Layer 2 — Device attestation (defends A3)

Before presenting, the wallet calls Google **Play Integrity** (classic request with server-issued nonce). The token carries hardware-backed verdicts:

- `deviceIntegrity` = `MEETS_DEVICE_INTEGRITY` + `MEETS_STRONG_INTEGRITY`
- `appIntegrity` = `PLAY_RECOGNIZED`
- `playProtectVerdict` = `NO_ISSUES`

The token is cryptographically bound to `SHA-256(KB-JWT payload)` — cannot be reused. Backend (not client) decrypts via Google's `decodeIntegrityToken`. A rooted phone, custom ROM, or emulator either fails to produce a token or fails the verdict — issuer refuses to issue, verifier refuses to accept.

*Residual risk: Play Integrity is high-bar but not impenetrable; it raises attacker cost from "trivial" to "expensive and tool-dependent".*

### Layer 3 — Person binding (defends A4, A5, A6)

The credential carries a **hash of a low-resolution reference face photo** captured at issuance under a liveness challenge (MediaPipe: head turn + random-direction blink + color flash). The **hash, not the photo**, enters the credential. The photo lives at issuer CDN behind a short-lived signed URL.

At verification:
1. Fetch photo via signed URL.
2. Recompute hash, compare to credential `photo_hash` — **this alone kills A6**.
3. Run 2–3s live check on the person present; compare to fetched reference.

Against A5 (real-time deepfake), the verifier-side liveness will always be an arms race. StudentZK's answer: **raise attacker cost**. An A5 attacker must *also* defeat Layer 1 and Layer 2. Defeating all three is materially harder than any single legacy KYC system.

## What StudentZK explicitly does NOT do

- **No biometric templates** on server or verifier. The photo is a photo, not a template; it never enters a matcher database. Avoids GDPR Article 9 special-category processing.
- **No home-grown deepfake detector.** Those rot fast. Upstream liveness (controlled issuance moment) + cryptographic layers only.

## Out of scope

- **A7** (social engineering): the holder voluntarily shares. This is unwinnable at the cryptographic layer. Mitigated only by user education and per-presentation user consent prompts ("you are about to reveal: *is_student*, *age ≥ 18*, *photo hash*; you are NOT revealing: *name*, *DOB*, *student ID*").

## Minimal backend surface (§5.8)

- `POST /integrity/nonce` → issue single-use nonce bound to subject_did
- `POST /integrity/verify` → decode Google token, record `integrity_assertions` row
- `integrity_assertions` table — unique on nonce; one row per verified token.
