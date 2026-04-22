# Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        Issuer                               │
│               (issuer-backend / Spring Boot)                │
│                                                             │
│  FER Zagreb student DB  →  SD-JWT-VC / BBS+ credential      │
└──────────────────────────────┬──────────────────────────────┘
                               │
                          OID4VCI
                  (Credential Offer → Token → Credential)
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                        Holder                               │
│               (holder-android / EUDI Wallet)                │
│                                                             │
│  Stores credential · Selects attributes to disclose         │
└──────────────────────────────┬──────────────────────────────┘
                               │
                          OID4VP
                  (Authorization Request → VP Token)
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                       Verifier                              │
│               (verifier-web / React + Vite)                 │
│                                                             │
│  QR scan → parse VP Token → verify proof → show attributes  │
└─────────────────────────────────────────────────────────────┘
```

## Credential Schema

```
Credential attributes:
  - student_id        (string, always hidden)
  - university_id     (string, selectively disclosable)
  - given_name_hash   (string, hidden — raw name stays in issuer DB only)
  - family_name_hash  (string, hidden)
  - photo_hash        (string, selectively disclosable)
  - is_student        (boolean, selectively disclosable)
  - age_over_18       (boolean, selectively disclosable)  ← pre-computed by issuer
  - valid_until       (date, always disclosed)
```

## Phase Plan

| Phase | Timeframe | Goal |
|-------|-----------|------|
| **Phase 0** | Days 1–2 | Repo setup, Rust crypto build scaffold, DB schema |
| **Phase 1** | Week 1–2 | SD-JWT-VC happy path end-to-end (issue → hold → verify) |
| **Phase 2** | Week 2–4 | BBS+ selective disclosure via crypto-core JNI |
| **Phase 3** | Week 4–6 | Polish: photo hash, revocation (Token Status List), batch issuance, admin UI |

> **If time compresses, drop BBS and ship SD-JWT-VC only.**
