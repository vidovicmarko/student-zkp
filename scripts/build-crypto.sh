#!/usr/bin/env bash
# Builds the crypto-core cdylib for the host JVM and (if cargo-ndk is on PATH)
# for Android ABIs. Drops outputs in the locations the issuer JVM and the
# holder Android app expect.
#
#   - Host:    crypto-core/target/release/{libstudentzkp_crypto.so,libstudentzkp_crypto.dylib,studentzkp_crypto.dll}
#              copied to issuer-backend/build/native/
#   - Android: holder-android/StudentZK/app/src/main/jniLibs/<abi>/libstudentzkp_crypto.so
#
# Prereqs:
#   * Rust 1.75+
#   * For Android: `cargo install cargo-ndk` and ANDROID_NDK_HOME set
#     (or have the NDK installed via Android Studio SDK Manager).
#
# Usage:
#   scripts/build-crypto.sh             # host + Android (if NDK available)
#   scripts/build-crypto.sh host        # host only
#   scripts/build-crypto.sh android     # Android only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRYPTO="$ROOT/crypto-core"
ISSUER_NATIVE="$ROOT/issuer-backend/build/native"
JNILIBS="$ROOT/holder-android/StudentZK/app/src/main/jniLibs"

mode="${1:-all}"

build_host() {
    echo "==> Building crypto-core for host JVM ($(uname -s))"
    (cd "$CRYPTO" && cargo build --release)

    mkdir -p "$ISSUER_NATIVE"
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) lib="studentzkp_crypto.dll" ;;
        Darwin)               lib="libstudentzkp_crypto.dylib" ;;
        *)                    lib="libstudentzkp_crypto.so" ;;
    esac
    cp -v "$CRYPTO/target/release/$lib" "$ISSUER_NATIVE/$lib"
    echo "    -> $ISSUER_NATIVE/$lib"
}

build_android() {
    if ! command -v cargo-ndk >/dev/null 2>&1; then
        echo "WARN: cargo-ndk not on PATH — skipping Android build."
        echo "      Install with: cargo install cargo-ndk"
        return 0
    fi
    if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${NDK_HOME:-}" && -z "${ANDROID_NDK_ROOT:-}" ]]; then
        echo "WARN: ANDROID_NDK_HOME / NDK_HOME / ANDROID_NDK_ROOT not set — skipping Android build."
        return 0
    fi

    # 64-bit ABIs only. The JNA bridge maps `size_t` to Java `long` (8 bytes),
    # which only matches arm64-v8a and x86_64. Building for armv7/x86 here
    # would produce loadable .so files whose BBS+ entry points would corrupt
    # the call frame at the first FFI call.
    echo "==> Cross-compiling crypto-core for Android (arm64-v8a, x86_64)"
    mkdir -p "$JNILIBS"
    (cd "$CRYPTO" && cargo ndk \
        --target aarch64-linux-android \
        --target x86_64-linux-android \
        --output-dir "$JNILIBS" \
        build --release)

    echo "    -> $JNILIBS/{arm64-v8a,x86_64}/libstudentzkp_crypto.so"
}

case "$mode" in
    host)    build_host ;;
    android) build_android ;;
    all)     build_host; build_android ;;
    *)       echo "Usage: $0 [host|android|all]" >&2; exit 2 ;;
esac

echo "Done."
