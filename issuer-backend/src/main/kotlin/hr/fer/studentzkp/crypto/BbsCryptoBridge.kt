package hr.fer.studentzkp.crypto

import com.sun.jna.Library
import com.sun.jna.Native

// JNA binding to the docknetwork/crypto-backed `studentzkp-crypto` Rust cdylib
// (final_plan §5.2). The same .so/.dylib is consumed on Android via UniFFI.
// All functions are stubs until crypto-core lib.rs drops its todo!() bodies
// in Phase 2.
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
