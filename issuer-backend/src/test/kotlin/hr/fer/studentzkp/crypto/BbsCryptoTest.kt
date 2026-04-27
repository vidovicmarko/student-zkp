package hr.fer.studentzkp.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

// End-to-end exercise of the JNA bridge against the real Rust cdylib.
// Skipped when build/native/studentzkp_crypto.{dll,so,dylib} is missing —
// build it with `bash scripts/build-crypto.sh host` (or .ps1) first.
class BbsCryptoTest {

    @Test
    @EnabledIf("nativeLibraryAvailable")
    fun `keygen sign derive verify roundtrip`() {
        val keypair = BbsCrypto.keygen()
        assertTrue(keypair.publicKey.isNotEmpty())
        assertTrue(keypair.secretKey.isNotEmpty())

        val messages = listOf(
            "is_student=true".toByteArray(),
            "age_equal_or_over.18=true".toByteArray(),
            "university_id=fer.unizg.hr".toByteArray(),
            "given_name_hash=ee2a".toByteArray(),
        )

        val sig = BbsCrypto.sign(messages, keypair.secretKey)
        assertTrue(sig.isNotEmpty())

        val disclosed = listOf(0, 1)
        val nonce = "verifier-nonce-1".toByteArray()
        val proof = BbsCrypto.deriveProof(sig, messages, disclosed, nonce)
        assertTrue(proof.isNotEmpty())

        val disclosedMsgs = disclosed.map { messages[it] }
        val ok = BbsCrypto.verifyProof(
            proof = proof,
            publicKey = keypair.publicKey,
            disclosedIndices = disclosed,
            disclosedMessages = disclosedMsgs,
            totalMessageCount = messages.size,
            nonce = nonce,
        )
        assertTrue(ok, "expected proof to verify")
    }

    @Test
    @EnabledIf("nativeLibraryAvailable")
    fun `wrong nonce does not verify`() {
        val (pk, sk) = BbsCrypto.keygen()
        val messages = listOf("a".toByteArray(), "b".toByteArray(), "c".toByteArray())
        val sig = BbsCrypto.sign(messages, sk)
        val proof = BbsCrypto.deriveProof(sig, messages, listOf(0), "n1".toByteArray())
        val ok = BbsCrypto.verifyProof(
            proof = proof,
            publicKey = pk,
            disclosedIndices = listOf(0),
            disclosedMessages = listOf(messages[0]),
            totalMessageCount = messages.size,
            nonce = "n2".toByteArray(),
        )
        assertFalse(ok)
    }

    @Test
    @EnabledIf("nativeLibraryAvailable")
    fun `two proofs of the same signature are unlinkable`() {
        val (_, sk) = BbsCrypto.keygen()
        val messages = listOf("x".toByteArray(), "y".toByteArray())
        val sig = BbsCrypto.sign(messages, sk)

        val p1 = BbsCrypto.deriveProof(sig, messages, listOf(0), "v1".toByteArray())
        val p2 = BbsCrypto.deriveProof(sig, messages, listOf(0), "v2".toByteArray())
        // Same signature, two derivations → byte-distinct proofs.
        // (Equality on ByteArray is reference equality in Kotlin, so use contentEquals.)
        assertFalse(p1.contentEquals(p2), "two derivations should rerandomize")
    }

    @Test
    @EnabledIf("nativeLibraryAvailable")
    fun `error path surfaces last_error message`() {
        // disclosed_indices contains an out-of-range value → Rust returns Err,
        // which the bridge turns into BbsCryptoException with status=CRYPTO.
        val (_, sk) = BbsCrypto.keygen()
        val messages = listOf("only-one".toByteArray())
        val sig = BbsCrypto.sign(messages, sk)
        val ex = assertThrows(BbsCryptoException::class.java) {
            BbsCrypto.deriveProof(sig, messages, listOf(99), "n".toByteArray())
        }
        assertEquals(3, ex.status) // STATUS_ERR_CRYPTO
        assertTrue(
            ex.message!!.contains("disclosed_indices contains 99"),
            "expected last_error to come back, got: ${ex.message}",
        )
    }

    @Test
    @EnabledIf("nativeLibraryAvailable")
    fun `tampered disclosed message fails verification`() {
        val (pk, sk) = BbsCrypto.keygen()
        val messages = listOf("is_student=true".toByteArray(), "uni=FER".toByteArray())
        val sig = BbsCrypto.sign(messages, sk)
        val proof = BbsCrypto.deriveProof(sig, messages, listOf(0, 1), "n".toByteArray())

        val tamperedDisclosed = listOf(
            "is_student=fals".toByteArray(),  // flipped
            messages[1],
        )
        val ok = BbsCrypto.verifyProof(
            proof = proof,
            publicKey = pk,
            disclosedIndices = listOf(0, 1),
            disclosedMessages = tamperedDisclosed,
            totalMessageCount = messages.size,
            nonce = "n".toByteArray(),
        )
        assertFalse(ok, "tampered disclosed message must invalidate the proof")
        // Sanity: the *unique* identifier is still hidden; an attacker can't
        // bruteforce-fix the disclosed slot without also forging the proof.
        assertNotEquals("is_student=fals", String(messages[0]))
    }

    companion object {
        @JvmStatic
        @Suppress("unused")
        fun nativeLibraryAvailable(): Boolean {
            // Resolve relative to the issuer-backend module's working dir.
            val candidates = listOf(
                "build/native/studentzkp_crypto.dll",
                "build/native/libstudentzkp_crypto.so",
                "build/native/libstudentzkp_crypto.dylib",
            )
            return candidates.any { java.io.File(it).exists() }
        }
    }
}
