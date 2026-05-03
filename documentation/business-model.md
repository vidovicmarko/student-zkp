# StudentZK — Business Model

**Project:** StudentZK  
**Date:** May 2026

---

## 1. Innovation — How StudentZK Stands Out

Every existing competitor (PVID Card, ID123, Student ID+, AKD Certilia) is a **visual ID card on a phone** — a branded photo with a barcode that a human employee looks at. StudentZK is a fundamentally different architecture: a cryptographic proof designed for machines.

### Six properties no competitor combines

**1. Prove a predicate, not an identity.**  
A student can prove "I am a student of a Croatian university" and "I am 18 or older" without revealing their name, date of birth, university, or student number. Before every scan the wallet explicitly shows the student what is being disclosed and what is being withheld. No existing student credential solution does this.

**2. Machine-verifiable at unattended terminals.**  
A Croatian Railways ticket vending machine, a museum kiosk, or a cinema turnstile can scan a StudentZK QR code and receive a cryptographic proof in milliseconds — with no integration contract, no issuer account, no server call beyond fetching a public cached file. The verifier is a URL that anyone can bookmark and open on any device.

**3. Cryptographic unlinkability via BBS+.**  
Two presentations of the same BBS-2023 credential produce mathematically unrelated proofs. Even if two different verifiers share all their data, they cannot determine the presentations came from the same person. This is not just a privacy feature — it is a fundamental architectural property enforced by the mathematics of BLS12-381 rerandomisation. No competitor in the student space offers this.

**4. eIDAS 2.0 / EUDI Wallet aligned from day one.**  
StudentZK speaks OpenID4VCI, OpenID4VP, SD-JWT-VC, and DCQL — the exact protocols mandated by the EU Architecture Reference Framework (ARF 2.x). When Croatia's national EUDI wallet launches (Q4 2026 target), StudentZK's verifier can accept national PID credentials with a one-afternoon update. The emerging EUDI ecosystem becomes distribution, not competition.

**5. Platform-agnostic credential registry.**  
The same infrastructure issues student credentials today, library cards tomorrow, youth transit passes next week. Adding a new credential type is a single database row insert — no code changes anywhere in the stack. One wallet, many contexts, one verification platform.

**6. Three-layer defence-in-depth.**  
Hardware key binding (StrongBox), device attestation (Play Integrity), and liveness-checked biometric binding are three independent cryptographic layers. An attacker must defeat all three simultaneously. No other student credential solution in Europe implements all three. This is not over-engineering — each layer defends against a different class of real attack.

---

## 2. Monetization

StudentZK separates **issuers** (universities, who pay nothing) from **verifiers** (businesses, who are the revenue source). This is the standard platform playbook: subsidise the supply side to accelerate network growth; monetise the demand side.

### Pricing tiers

| Tier | Target customer | Price | What is included |
|---|---|---|---|
| **Free verifier** | Individuals, very small operators | €0 / month | Up to 100 verifications/month, hosted verifier SPA |
| **Standard verifier** | Bars, cafés, small museums, retail | €19 / month | Unlimited verifications, custom branding, basic analytics |
| **Professional verifier** | Cinema chains, transport operators, employers | €99 / month | DCQL custom claim sets, REST API access, multi-location dashboard, SLA |
| **Enterprise / B2G** | HŽ, public libraries, government agencies | Custom (est. €500–5,000 / month) | On-premise deployment, dedicated support, custom credential types |
| **Per-verification billing** | High-volume API integrations | €0.20 / verification | E-commerce student discount gating, online ticketing, overflow |

Universities (issuers) are not charged. Their participation builds the credential supply without any procurement budget being required on their side.

### Revenue streams

**Verifier SaaS subscriptions** — primary revenue. Every business that wants to offer student or age-verified discounts automatically without a human in the loop is a potential customer. The density of bars, museums, cinemas, and transport operators in Croatia alone represents hundreds of addressable Standard/Professional accounts.

**B2G contracts** — Croatian Railways (HŽ), Croatian National and University Library (NSK), public museums. Two or three such logos anchor credibility, generate reference revenue, and open public procurement channels. The B2G unit value is much higher (Enterprise tier) with longer contract durations.

**API billing** — high-volume integrations: e-commerce platforms gating student discounts at checkout, online ticketing with youth fare verification, age-gating for alcohol or gambling services. Per-verification pricing at €0.20 aligns revenue with usage.

**EUDI interoperability premium** — as EUDI wallets launch across Europe in 2026–2027, StudentZK's verifier becomes the easiest way for Croatian businesses to accept national digital identity. This opens a second growth vector without changing the product architecture.

### Unit economics

| Metric | Value |
|---|---|
| Cost per verification | < €0.001 — JWKS + status list served from CDN; all proof verification runs client-side in the verifier's browser |
| Gross margin at Standard tier | > 95% |
| Path to €1M ARR | ~880 Standard customers, or ~10 Enterprise + ~400 Standard |
| Total addressable students (Croatia) | ~100,000+ active university students |
| Verifier TAM (Croatia only) | Bars, restaurants, cinemas, museums, transport: thousands of locations |

The cost structure is nearly pure-margin at scale because the compute-intensive step (BBS+ proof verification) runs in the verifier's browser — StudentZK's infrastructure serves only small static files (JWKS, status list) and lightweight issuance API calls.

---

## 3. Future Development

### Short term — 0 to 6 months

**Liveness-checked selfie at issuance.**  
MediaPipe head-turn + blink + color flash challenge at enrollment. Closes the last open gap before the full three-layer defence is active. The issuer signs `SHA-256(lowResPhoto)` into the credential; the photo lives at a short-lived signed URL.

**Live face-match at verification.**  
The verifier fetches the signed photo URL, recomputes the hash against `photo_hash` in the credential, and runs `face-api.js` live comparison against the camera feed. Layer 3 complete.

**Admin UI.**  
Web dashboard for universities to issue credentials in bulk, revoke credentials, register new credential types, and monitor status list health. Currently managed via API calls; a UI is required for production deployments.

**Biometric unlock before presentation.**  
Require fingerprint or face ID before the StrongBox key signs the KB-JWT. Prevents a stolen unlocked phone from presenting someone else's credential.

**Batch issuance.**  
Issue N single-use SD-JWT-VCs per student so the SD-JWT-VC path also achieves unlinkability for short-term comparison parity with BBS+. This mirrors the EUDI PID model.

### Medium term — 6 to 18 months

**EUDI Wallet interoperability.**  
Update the verifier to accept national PID and EAA credentials from Croatian and European EUDI wallets when those launch (Croatia target: Q4 2026). StudentZK becomes the business-facing verification layer for the entire EUDI ecosystem — EUDI's rollout becomes tailwind rather than competition.

**Transit integration pilot.**  
Deploy at HŽ ticket vending machines for automated youth fare verification. The first fully unattended, privacy-preserving machine verification of student status in Croatia. This is the demo that wins the B2G contract.

**Library network rollout.**  
Enroll the Croatian National and University Library (NSK) and local branch networks as the second issuer type. Every library card holder gets a cryptographic membership credential in the same wallet app — proving the platform is genuinely multi-issuer.

**ISO 18013-5 (mdoc) support.**  
The third EUDI ARF-mandated credential format alongside SD-JWT-VC and BBS-2023. Enables NFC proximity verification (no camera needed). Completes full ARF coverage.

**ZK revocation (accumulator-based).**  
Replace the public bitstring status list with a cryptographic KB-accumulator. Revocation becomes zero-knowledge: the verifier learns only that the presented credential is still valid, not which credential was revoked. Stubs already exist in `crypto-core/src/lib.rs`.

### Long term — 18+ months

**University degree credentials.**  
Align with the DC4EU education pilot and ISeVO (Croatia's integrated digital diploma register). Issue cryptographic degree certificates so graduates can prove qualifications to employers cross-border without sending PDF scans. Same wallet, same protocols, new credential type.

**Post-Quantum Cryptography (PQC) migration.**  
The EUDI ARF flags PQC as a future requirement. The `docknetwork/crypto` Rust crate and the JOSE layer both have defined migration paths. StudentZK's layered architecture makes this a library upgrade, not a platform redesign — the application code does not touch raw cryptographic primitives.

**B2C premium wallet tier.**  
Optional paid tier for students: presentation history, multiple identities from different issuers, cross-device sync, biometric-protected cloud backup. Revenue without adding verifier customers.

**Privacy-preserving verifier analytics.**  
Aggregate statistics for operators (verifications per day, peak hours, credential type breakdown) computed without collecting any personal data — using zero-knowledge proofs of aggregate counts. Differentiator for GDPR-sensitive markets.

---

## 4. Strategic Defensibility

StudentZK grows more difficult to replicate as the platform matures:

**Network effects.** More issuers (universities) → more credential types → more wallet users → more verifier demand → more verifiers → more value for students. A classic two-sided network where every new issuer makes the verifier side more attractive.

**Protocol lock-in.** eIDAS 2.0 standardisation locks in the protocols StudentZK already speaks. Late entrants must build against a moving spec; StudentZK already has production implementations of OID4VCI, OID4VP, and both EUDI-mandated credential formats.

**Verifier switching costs.** Custom DCQL configurations, analytics integrations, multi-location dashboards, and API dependencies create meaningful switching costs on the verifier side. A bar chain that configures age-gate verification for 30 locations does not casually change platforms.

**Open-source crypto core.** The Rust `crypto-core` library is open source. Universities and government agencies can audit every cryptographic operation. No proprietary competitor can offer this level of institutional trust — and institutional trust is exactly what is needed to win B2G contracts.

**First-mover in Croatia.** Being first to a B2G contract with HŽ or NSK creates a reference that makes subsequent public procurement much easier. Public institutions are reluctant to be the first adopter; they are very willing to be the third.
