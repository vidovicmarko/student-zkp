# StudentZK

StudentZK — privacy-preserving student credential verification using BBS+ and SD-JWT-VC, aligned with eIDAS 2.0 / EUDI Wallet.

## Modules

| Module | Stack | Description |
|--------|-------|-------------|
| `issuer-backend` | Kotlin + Spring Boot | Issues SD-JWT-VC and BBS+ credentials to students |
| `verifier-web` | React + TypeScript + Vite | Browser-based verifier that scans QR codes |
| `holder-android` | Android (Kotlin + Compose) | Student wallet app (create in Android Studio) |
| `crypto-core` | Rust | BBS+ cryptographic primitives (JNI wrapper) |

## Prerequisites

- JDK 21+
- Node 20+
- Rust 1.75+
- PostgreSQL 16+
- Android Studio (Hedgehog or newer)

## Quick Start

### issuer-backend

```bash
cd issuer-backend
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
# edit application-local.yml with your DB credentials
./gradlew build
./gradlew bootRun
```

### verifier-web

```bash
cd verifier-web
npm install
npm run dev
```

### crypto-core

```bash
cd crypto-core
cargo check
cargo test
```

### holder-android

See `holder-android/README.md` — create the project in Android Studio first.
