# holder-android

This module must be created from Android Studio — do not generate Android project files manually.

## Setup

1. Open Android Studio and select **New Project → Empty Activity (Compose)**.
2. Set package name: `hr.fer.studentzkp.holder`
3. Set minimum SDK: **API 29 (Android 10)**
4. Place the project inside this `holder-android/` directory.

## Key Dependencies to Add

After creation, add these to your `app/build.gradle.kts`:

```kotlin
// Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.02.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")

// CameraX
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")
implementation("androidx.camera:camera-view:1.3.1")

// ML Kit Barcode scanning
implementation("com.google.mlkit:barcode-scanning:17.2.0")

// ZXing core (fallback / extra formats)
implementation("com.google.zxing:core:3.5.3")
```

## Rust UniFFI Integration

1. Build the Rust crate for Android targets:

```bash
cd ../crypto-core
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86_64 -o ../holder-android/app/src/main/jniLibs build --release
```

2. Generate Kotlin bindings with UniFFI:

```bash
cargo run --features=uniffi/cli --bin uniffi-bindgen generate \
  src/studentzkp_crypto.udl \
  --language kotlin \
  --out-dir ../holder-android/app/src/main/java
```

> UniFFI and BBS+ integration is Phase 2/3 work. The `crypto-core` crate currently contains `todo!()` stubs.
