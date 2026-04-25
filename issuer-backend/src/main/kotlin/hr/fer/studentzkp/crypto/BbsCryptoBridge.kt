package hr.fer.studentzkp.crypto

import com.sun.jna.Library
import com.sun.jna.Native

// JNA binding to the docknetwork/crypto-backed `studentzkp-crypto` Rust cdylib
// (final_plan §5.2). The same .so/.dylib is consumed on Android via UniFFI.
//
// Status: the Rust BBS+ implementation is complete and tested (cargo test in
// crypto-core/), but the C-ABI wrapper layer is NOT yet written. JNA cannot
// call Rust's idiomatic `Vec<u8>` / `Result<T, String>` returns directly — it
// needs `#[no_mangle] extern "C"` shims with raw pointers + lengths + a free
// fn. That wrapper is the last mile before the JVM can call into the Rust
// core; tracked under "JNI/UniFFI shim" in ROADMAP Phase 2.
interface BbsCryptoBridge : Library {

    fun bbs_keygen(): KeypairBytes
    fun bbs_sign(messages: ByteArray, messageCount: Int, secretKey: ByteArray): ByteArray
    fun bbs_derive_proof(
        signature: ByteArray,
        messages: ByteArray,
        messageCount: Int,
        disclosedIndices: IntArray,
        disclosedCount: Int,
        nonce: ByteArray,
    ): ByteArray
    fun bbs_verify_proof(
        proof: ByteArray,
        publicKey: ByteArray,
        disclosedMessages: ByteArray,
        disclosedCount: Int,
        nonce: ByteArray,
    ): Boolean

    companion object {
        private const val LIB_NAME = "studentzkp_crypto"

        val INSTANCE: BbsCryptoBridge by lazy {
            Native.load(LIB_NAME, BbsCryptoBridge::class.java)
        }
    }
}

class KeypairBytes {
    @JvmField var publicKey: ByteArray = ByteArray(0)
    @JvmField var secretKey: ByteArray = ByteArray(0)
}
