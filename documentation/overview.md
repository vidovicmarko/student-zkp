# StudentZK — Project Overview

**Project:** StudentZK  
**Date:** May 2026  
**Version:** v2

---

## 1. The Problem

Today's student identification is built on trust in a visual card — a photo, a name, a barcode — that a human cashier or security guard looks at and decides to accept. This approach has four fundamental problems:

1. **Privacy:** Every verification reveals the student's full name, date of birth, institution, and student number — far more than the verifier actually needs to know.
2. **Forgery risk:** Visual cards can be copied, faked, or shared with friends.
3. **No machine verification:** Unattended QR scanners at train ticket vending machines, museum kiosks, or bar entry systems cannot reliably verify a visual card.
4. **No standard:** Every university, every transport operator, every library builds its own incompatible system.

## 2. The Solution

StudentZK replaces the visual card with a **cryptographic credential that proves a predicate** — "this person is a student", "this person is 18 or older" — **without revealing who that person is**.

A verifier (bar, museum, train station, online service) learns exactly what it needs to know and nothing else. The student's name, date of birth, student number, and home university never leave the student's phone. Verification takes milliseconds and runs as a static web page — no integration, no account, no data collected.

**The core innovation in one sentence:** StudentZK is the first system that combines (a) zero-knowledge selective disclosure, (b) an offline-capable verifier, (c) alignment with the European digital identity standard (eIDAS 2.0), and (d) hardware-bound, biometric-protected credentials — in a single open platform that any verifier can use for free.

## 3. Competitive Landscape

| Product | Selective Disclosure | Offline Verifier | Unlinkable | eIDAS 2.0 | Anti-deepfake | Multi-document |
|---|---|---|---|---|---|---|
| PVID Card | No | No | No | No | No | No |
| ID123 | No | Display only | No | No | No | Partial |
| Student ID+ (Apple/Google Wallet) | No | NFC offline | No | No | Device PIN only | Partial |
| AKD Certilia (Croatia) | Partial | No | No | Planned | Biometric unlock | Yes |
| EUDI Wallet RI | Yes (SD-JWT-VC) | Yes | No | Yes | Device biometric | Yes |
| **StudentZK** | **Yes** | **Yes** | **Yes (BBS+)** | **Yes** | **Yes (3 layers)** | **Yes** |

All existing competitors are visual ID cards on a phone — designed for a human to look at. StudentZK is the opposite: a cryptographic proof designed for machines to verify. No competitor combines all six properties above. That is the moat.

### The four gaps StudentZK fills

**1. "Machine-verifiable student discount" is unsolved.**  
Nobody sells a solution that a Croatian Railways (HŽ) ticket vending machine or a museum self-service kiosk can verify automatically without collecting personal data.

**2. "Prove a predicate, not an identity" is unsolved in the student space.**  
Every existing card reveals the holder's full identity. StudentZK reveals only what the verifier asked for. The student's app shows them exactly what is being disclosed and what is being withheld before every scan.

**3. "Works when the verifier has no integration" is unsolved.**  
Competitors require the verifier to register in the issuer's system. StudentZK's verifier is a static web page: any bar, shop, or event operator opens the URL and scans. No onboarding, no account, no SDK.

**4. "Anti-deepfake + privacy together" is unsolved.**  
Biometric-bound wallets solve anti-deepfake but at the cost of storing biometrics server-side. StudentZK stores only a photo hash in the credential and a low-res photo behind a short-lived signed URL — no biometric database, no GDPR Article 9 risk.

## 4. Document Map

| Document | Contents |
|---|---|
| `overview.md` *(this file)* | Project idea, motivation, competitive landscape |
| `technical-documentation.md` | Architecture, technologies, how issuance/presentation/verification works, security model |
| `setup-guide.md` | From-zero setup instructions, API reference, test scenarios |
| `business-model.md` | Innovation, monetization, future development |
