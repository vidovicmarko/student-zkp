# StudentZK — Project Scaffold Specification

**Purpose:** Hand this file to a Claude agent (or similar) so it can generate the full project skeleton in one pass. Everything here is intentional — do not add features, libraries, or logic beyond what is listed. The goal is a clean starting point that compiles/runs with zero conflicts.

---

## 1. Repo structure

Create a monorepo with this layout:

```
student-zkp/
├── README.md
├── .gitignore
├── issuer-backend/          # Kotlin + Spring Boot
├── verifier-web/            # React + TypeScript + Vite
├── holder-android/          # Placeholder — created in Android Studio later
├── crypto-core/             # Rust crate (BBS+ wrapper, stubbed for now)
└── docs/
    └── architecture.md
```

---

## 2. Root files

### `.gitignore`

Cover: Java/Kotlin (`build/`, `.gradle/`, `*.class`, `*.jar`), Node (`node_modules/`, `dist/`), Rust (`target/`), Android (`local.properties`, `build/`), IDE (`.idea/`, `.vscode/`), secrets (`*.env`, `application-local.yml`), OS (`.DS_Store`).

### `README.md`

Short project description: "StudentZK — privacy-preserving student credential verification using BBS+ and SD-JWT-VC, aligned with eIDAS 2.0 / EUDI Wallet."

List the four modules, prerequisites (JDK 21+, Node 20+, Rust 1.75+, PostgreSQL 16+, Android Studio), and quick-start commands for each module.

---

## 3. `issuer-backend/` — Kotlin + Spring Boot

### Build system: Gradle (Kotlin DSL)

- Kotlin 2.1+, JVM 21
- Spring Boot 3.4+
- Plugins: `kotlin("jvm")`, `kotlin("plugin.spring")`, `kotlin("plugin.jpa")`, `org.springframework.boot`, `io.spring.dependency-management`

### Dependencies (only these — nothing extra)

```
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-security
spring-boot-starter-actuator
jackson-module-kotlin
kotlin-reflect
postgresql (runtimeOnly)
nimbus-jose-jwt 10.x
spring-boot-starter-test (test)
kotlin-test-junit5 (test)
```

Add these as TODO comments (commented out), not active dependencies:
- `eu.europa.ec.eudi:eudi-lib-jvm-openid4vci-kt`
- `eu.europa.ec.eudi:eudi-lib-jvm-sdjwt-kt`
- `net.java.dev.jna:jna` (for Rust JNI)

### Package: `hr.fer.studentzkp`

Create these sub-packages with placeholder files:

#### `StudentZkpApplication.kt`
Standard `@SpringBootApplication` entry point.

#### `config/SecurityConfig.kt`
- Disable CSRF for now (comment: "will configure for OID4VCI later")
- Permit `/health`, `/actuator/health`, `/.well-known/**`
- Permit all other requests for now (comment: "lock down once auth is wired")

#### `model/Student.kt`
JPA entity mapping to `students` table:
- `id: UUID` (generated)
- `studentId: String` (unique)
- `universityId: String`
- `givenName: String`
- `familyName: String`
- `dateOfBirth: LocalDate`
- `photoUrl: String?`
- `isActive: Boolean` (default true)
- `createdAt: OffsetDateTime`
- `updatedAt: OffsetDateTime`

#### `model/IssuedCredential.kt`
JPA entity mapping to `issued_credentials` table:
- `id: UUID` (generated)
- `student: Student` (ManyToOne)
- `credentialType: String` — "SD_JWT_VC" or "BBS"
- `statusIndex: Int` — position in Token Status List
- `issuedAt: OffsetDateTime`
- `expiresAt: OffsetDateTime?`
- `revoked: Boolean` (default false)
- `revokedAt: OffsetDateTime?`

#### `repository/StudentRepository.kt`
JpaRepository with `findByStudentId(studentId: String): Student?`

#### `repository/IssuedCredentialRepository.kt`
JpaRepository with `findByStudentId(studentId: UUID): List<IssuedCredential>`

#### `controller/HealthController.kt`
Single `GET /health` endpoint returning `{"status": "ok", "service": "student-zkp-issuer"}`.

#### `service/` — empty package
Create a `.gitkeep` or a single empty `CredentialService.kt` interface:
```kotlin
interface CredentialService {
    // TODO: Phase 1 — SD-JWT-VC issuance
    // TODO: Phase 2 — BBS+ issuance
}
```

### Resources

#### `application.yml`
```yaml
spring:
  application.name: student-zkp-issuer
  datasource:
    url: jdbc:postgresql://localhost:5432/studentzkp
    username: ${DB_USERNAME:studentzkp}
    password: ${DB_PASSWORD:studentzkp}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
server:
  port: 8080
studentzkp:
  issuer:
    id: "https://issuer.studentzkp.hr"
    name: "FER Zagreb"
```

#### `application-local.example.yml`
Template for local DB credentials. Remind user to copy to `application-local.yml`.

#### `db/migration/V1__init_schema.sql`
```sql
CREATE TABLE students (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id    VARCHAR(50)  NOT NULL UNIQUE,
    university_id VARCHAR(100) NOT NULL,
    given_name    VARCHAR(200) NOT NULL,
    family_name   VARCHAR(200) NOT NULL,
    date_of_birth DATE         NOT NULL,
    photo_url     TEXT,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE issued_credentials (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id      UUID         NOT NULL REFERENCES students(id),
    credential_type VARCHAR(50)  NOT NULL,
    status_index    INTEGER      NOT NULL,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_issued_credentials_student ON issued_credentials(student_id);
CREATE INDEX idx_issued_credentials_status  ON issued_credentials(status_index);
```

### Tests

One basic context-loads test in `src/test/kotlin/hr/fer/studentzkp/`.

---

## 4. `crypto-core/` — Rust crate

### `Cargo.toml`
- Package name: `studentzkp-crypto`
- Edition 2021
- `crate-type = ["cdylib", "lib"]`
- Active deps: `serde` + `serde_json` only
- Commented-out deps (TODO): `dock_crypto 0.35` with `bbs_plus` feature, `uniffi 0.28`

### `src/lib.rs`
Define four public functions, all with `todo!()` bodies:
- `bbs_keygen() -> (Vec<u8>, Vec<u8>)`
- `bbs_sign(messages: &[Vec<u8>], secret_key: &[u8]) -> Vec<u8>`
- `bbs_derive_proof(signature, messages, disclosed_indices, nonce) -> Vec<u8>`
- `bbs_verify_proof(proof, public_key, disclosed_messages, nonce) -> bool`

Add doc comments explaining each function's purpose. One placeholder `#[test]`.

---

## 5. `verifier-web/` — React + TypeScript + Vite

### `package.json`
- Name: `studentzkp-verifier`, private, type module
- Deps: `react`, `react-dom` (v19)
- Dev deps: `@types/react`, `@types/react-dom`, `@vitejs/plugin-react`, `typescript ~5.7`, `vite ^6`
- Scripts: `dev`, `build`, `preview`

### `vite.config.ts`
React plugin, dev server on port 5173.

### `tsconfig.json`
Strict mode, target ES2020, jsx react-jsx, bundler module resolution.

### `index.html`
Standard Vite entry with `<div id="root">` and module script `/src/main.tsx`.

### `src/main.tsx`
ReactDOM.createRoot, StrictMode, renders `<App />`.

### `src/App.tsx`
Minimal shell with a state machine: `idle | scanning | verified | failed`.
- `idle`: shows a "Start Scanning" button
- `scanning`: placeholder div with TODO comment for @zxing/library QR scanner integration
- `verified`: green checkmark + placeholder for disclosed attributes
- `failed`: red X + "Try Again" button

No styling library — just inline styles or a single minimal CSS file. Keep it bare so the team can choose their own approach.

### `src/vite-env.d.ts`
Vite client type reference.

---

## 6. `holder-android/` — Placeholder only

Create only a `README.md` explaining:
1. This module should be created from Android Studio (Empty Compose Activity)
2. Package name: `hr.fer.studentzkp.holder`
3. Min SDK: API 29
4. List key dependencies to add (Compose BOM, CameraX, ML Kit barcode, ZXing core)
5. Instructions for Rust UniFFI integration (`cargo ndk` build command + `uniffi-bindgen` command)

Do NOT generate Android project files — Android Studio handles that better.

---

## 7. `docs/architecture.md`

Include:
- ASCII diagram of the three-party system (Issuer → Holder → Verifier) with protocols labeled (OID4VCI, OID4VP)
- Credential schema definition:

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

- Phase plan summary:
  - Phase 0 (days 1–2): Repo setup, Rust crypto build, DB schema
  - Phase 1 (week 1–2): SD-JWT-VC happy path end-to-end
  - Phase 2 (week 2–4): BBS+ selective disclosure
  - Phase 3 (week 4–6): Polish (photo, revocation, batch issuance, admin UI)
- Note: "If time compresses, drop BBS and ship SD-JWT-VC only."

---

## 8. What NOT to generate

- No Docker / docker-compose files (team can add later if they want)
- No CI/CD configs (premature)
- No linting configs beyond what Vite/Gradle include by default
- No authentication logic beyond the SecurityConfig shell
- No actual credential issuance or verification logic
- No UI styling framework (no Tailwind, no Material, no CSS-in-JS)
- No OID4VCI / OID4VP protocol implementation
- No Rust BBS+ implementation (just the `todo!()` stubs)

The skeleton must compile (`./gradlew build`, `cargo check`, `npm run build`) with zero errors. Use `todo!()` in Rust, empty method bodies or interfaces in Kotlin, and placeholder components in React for anything not yet implemented.
