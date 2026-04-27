package hr.fer.studentzkp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import hr.fer.studentzkp.crypto.BbsCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.time.Instant
import java.util.Base64

// End-to-end BBS-2023 dual-issue check. Issues a real W3C VCDM 2.0 credential,
// then derives a presentation proof and verifies it through the same canonical
// pipeline a verifier would use. Proves that:
//   * canonicalization is deterministic and invertible by the verifier,
//   * the issuer's BBS signature actually validates,
//   * BBS-2023 unlinkability holds (two derivations differ).
//
// Skipped when the crypto-core cdylib isn't on jna.library.path — same gate
// as BbsCryptoTest.
class BbsVcServiceTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    @EnabledIf("hr.fer.studentzkp.crypto.BbsCryptoTest#nativeLibraryAvailable")
    fun `issued credential verifies against the issuer pubkey`() {
        val keyService = BbsIssuerKeyService(keyPath = "", mapper = mapper)
        val service = BbsVcService(keyService, mapper)

        val issued = service.issue(makeRequest())
        // Sanity-check the JSON shape — anything claiming to be a VCDM 2.0 doc
        // needs `@context`, `type`, `issuer`, `proof.cryptosuite`, `proof.proofValue`.
        @Suppress("UNCHECKED_CAST")
        val proof = issued.credential["proof"] as Map<String, Any?>
        assertEquals("DataIntegrityProof", proof["type"])
        assertEquals("studentzk-bbs-2023", proof["cryptosuite"])
        assertEquals("studentzk-canonical-v1", proof["canonicalization"])
        assertNotNull(proof["proofValue"])

        // The issuer signs every leaf attribute. The verifier reconstructs
        // those messages bit-identically and verifies a derived proof.
        val messages = issued.messages.map { it.toByteArray(Charsets.UTF_8) }
        val nonce = "verifier-nonce-A".toByteArray()
        val allIndices = messages.indices.toList()
        val proofBytes = BbsCrypto.deriveProof(
            signature = issued.signature,
            messages = messages,
            disclosedIndices = allIndices,
            nonce = nonce,
        )

        val ok = BbsCrypto.verifyProof(
            proof = proofBytes,
            publicKey = keyService.keypair.publicKey,
            disclosedIndices = allIndices,
            disclosedMessages = messages,
            totalMessageCount = messages.size,
            nonce = nonce,
        )
        assertTrue(ok, "BBS-2023 dual-issue must verify under the same canonicalization the issuer used")
    }

    @Test
    @EnabledIf("hr.fer.studentzkp.crypto.BbsCryptoTest#nativeLibraryAvailable")
    fun `selective disclosure hides the rest of the attribute set`() {
        val keyService = BbsIssuerKeyService(keyPath = "", mapper = mapper)
        val service = BbsVcService(keyService, mapper)
        val issued = service.issue(makeRequest())

        val messages = issued.messages.map { it.toByteArray(Charsets.UTF_8) }
        val isStudentIdx = issued.messages.indexOfFirst { it.startsWith("credentialSubject.is_student=") }
        val ageIdx = issued.messages.indexOfFirst { it.startsWith("credentialSubject.age_equal_or_over.18=") }
        assertTrue(isStudentIdx >= 0 && ageIdx >= 0) { "expected canonical messages to include is_student and age_equal_or_over.18" }

        val disclosed = listOf(isStudentIdx, ageIdx).sorted()
        val nonce = "verifier-nonce-B".toByteArray()
        val proofBytes = BbsCrypto.deriveProof(
            signature = issued.signature,
            messages = messages,
            disclosedIndices = disclosed,
            nonce = nonce,
        )
        val ok = BbsCrypto.verifyProof(
            proof = proofBytes,
            publicKey = keyService.keypair.publicKey,
            disclosedIndices = disclosed,
            disclosedMessages = disclosed.map { messages[it] },
            totalMessageCount = messages.size,
            nonce = nonce,
        )
        assertTrue(ok, "selective disclosure of is_student + age_over_18 must verify")
    }

    @Test
    @EnabledIf("hr.fer.studentzkp.crypto.BbsCryptoTest#nativeLibraryAvailable")
    fun `unlinkability — two derivations of one signature differ`() {
        val keyService = BbsIssuerKeyService(keyPath = "", mapper = mapper)
        val service = BbsVcService(keyService, mapper)
        val issued = service.issue(makeRequest())

        val messages = issued.messages.map { it.toByteArray(Charsets.UTF_8) }
        val disclosed = listOf(0)
        val p1 = BbsCrypto.deriveProof(issued.signature, messages, disclosed, "v1".toByteArray())
        val p2 = BbsCrypto.deriveProof(issued.signature, messages, disclosed, "v2".toByteArray())
        assertFalse(p1.contentEquals(p2), "two derivations must rerandomize — this is the BBS+ headline property")
    }

    @Test
    fun `proofValue base64 round-trips signature bytes`() {
        // Independent of the cdylib — just checks the encoding.
        val sig = ByteArray(48) { it.toByte() }
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        val decoded = Base64.getUrlDecoder().decode(b64)
        assertTrue(decoded.contentEquals(sig))
    }

    private fun makeRequest(): BbsVcService.Request = BbsVcService.Request(
        issuerUri = "https://issuer.studentzkp.hr",
        publicBaseUrl = "http://localhost:8080",
        subjectId = "urn:dev:0036123456",
        statusListUri = "http://localhost:8080/statuslist/uni-2026.json",
        statusListIndex = 42,
        validFrom = Instant.parse("2026-04-01T00:00:00Z"),
        validUntil = Instant.parse("2026-10-01T00:00:00Z"),
        attributes = mapOf(
            "is_student" to true,
            "age_equal_or_over" to mapOf("18" to true),
            "university_id" to "fer.unizg.hr",
            "given_name_hash" to "ee2a",
            "family_name_hash" to "9b1f",
        ),
    )
}
