# holder-android

Android holder wallet for StudentZK. Generate from Android Studio — do not hand-author project files.

## Setup

1. Android Studio → **New Project → Empty Activity (Compose)**.
2. Package name: `hr.fer.studentzkp.holder`
3. **Min SDK: API 29** (Android 10) — required for StrongBox + Credential Manager (`final_plan §5.3`).
4. Place the project inside this `holder-android/` directory.

## Key dependencies

Add to `app/build.gradle.kts`:

```kotlin
// Compose
implementation(platform("androidx.compose:compose-bom:2024.02.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")

// CameraX + barcode
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")
implementation("androidx.camera:camera-view:1.3.1")
implementation("com.google.mlkit:barcode-scanning:17.2.0")
implementation("com.google.zxing:core:3.5.3")

// Device attestation (final_plan §5.8 Layer 2)
implementation("com.google.android.play:integrity:1.4.0")

// Liveness (final_plan §5.8 Layer 3) — swap for iBeta-L2 vendor in Phase 3
implementation("com.google.mediapipe:tasks-vision:0.10.14")

// SD-JWT-VC + OID4VP — Phase 1
// implementation("eu.europa.ec.eudi:eudi-lib-jvm-sdjwt-kt:0.9.0")
// implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vp-kt:0.9.0")

// Encrypted wallet storage
// implementation("org.openwallet.askar:askar-android:0.4.0")
```

## Hardware-bound key (final_plan §5.8 Layer 1)

Generate an ES256 keypair pinned to StrongBox (TEE fallback). The private key never leaves hardware; at presentation the wallet signs the Key-Binding JWT with it.

```kotlin
val spec = KeyGenParameterSpec.Builder(
    "studentzk.holder.cnf",
    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256)
    .setIsStrongBoxBacked(true)               // falls back to TEE if absent
    .setUserAuthenticationRequired(true)
    .setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG)
    .build()
KeyPairGenerator.getInstance("EC", "AndroidKeyStore").run {
    initialize(spec)
    generateKeyPair()
}
```

## Rust crypto via UniFFI (final_plan §5.2)

Build the shared cdylib from `../crypto-core/`:

```bash
cargo install cargo-ndk
cd ../crypto-core
cargo ndk -t arm64-v8a -t x86_64 -o ../holder-android/app/src/main/jniLibs build --release
```

Generate Kotlin bindings from the UDL:

```bash
cargo run --features=uniffi/cli --bin uniffi-bindgen generate \
  src/studentzkp_crypto.udl \
  --language kotlin \
  --out-dir ../holder-android/app/src/main/java
```

## Wallet UX (final_plan §5.9, Phase 3)

The wallet is **not** hardcoded to "Student card". Present a generic card stack — "University of Zagreb · Student", "HŽ · Youth fare", "National Library · Member", and an `+ add credential` entry. Every credential is a registered `credential_type` fetched from the issuer API.

At presentation time the UI must show the user exactly:
- what attributes are being revealed;
- what attributes are being held back;
- which verifier is asking;

before biometric confirmation releases the Key-Binding JWT signature.
