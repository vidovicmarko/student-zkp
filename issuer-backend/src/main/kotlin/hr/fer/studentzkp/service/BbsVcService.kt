package hr.fer.studentzkp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import hr.fer.studentzkp.crypto.BbsCrypto
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Base64
import java.util.SortedMap
import java.util.TreeMap

// W3C VCDM 2.0 dual-issuer using BBS+ on BLS12-381 (final_plan §5.2 / W3C
// vc-data-integrity §`bbs-2023`). The credential we mint is structurally a
// VCDM 2.0 JSON object — issuer, validFrom/validUntil, credentialSubject,
// credentialStatus, proof — so it slots into any VC tooling expecting that
// shape.
//
// Canonicalization (the part where bbs-2023 is heavy):
//   spec    : URDNA2015 over expanded JSON-LD → N-Quads → one BBS message per
//             quad. Requires a JSON-LD processor + RDF canonicalizer.
//   ours    : `studentzk-canonical-v1` — flatten every leaf attribute to
//             "<dot.path>=<json>" and sign that ordered list. Documented
//             explicitly in `proof.canonicalization` and pinned by `messages`
//             so the verifier reconstructs the exact same byte sequence.
//
// Why deviate: this is a competition demo, not an EUDI-interop deployment.
// The unlinkability story (BBS+ rerandomizes per derivation) lands either way,
// and our canonicalization is ~30 lines instead of ~3 transitive dependencies
// (jsonld-java + rdf4j + a URDNA2015 impl).
//
// Migration path: when the project goes to actual interop, swap `canonicalize`
// for a real URDNA2015 pipeline, leave the BBS pieces untouched. The proof
// envelope and key advertisement don't change.
@Service
class BbsVcService(
    private val keyService: BbsIssuerKeyService,
    private val mapper: ObjectMapper,
) {

    private val canonicalMapper = ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .also {
            // Match the JS canonical-JSON convention: no whitespace, ordered keys.
            it.factory.setCharacterEscapes(null)
        }

    data class Request(
        val issuerUri: String,
        val publicBaseUrl: String,
        val subjectId: String,
        val statusListUri: String,
        val statusListIndex: Int,
        val validFrom: Instant,
        val validUntil: Instant,
        val attributes: Map<String, Any?>,
    )

    data class IssuedBbsVc(
        /** The signed VC, ready to give to the holder, as a JSON object. */
        val credential: Map<String, Any?>,
        /** Canonical message list — what BBS+ actually signed. Same order as `proof.messages`. */
        val messages: List<String>,
        /** Raw BBS+ signature bytes (the same as `credential.proof.proofValue` decoded). */
        val signature: ByteArray,
    )

    fun issue(req: Request): IssuedBbsVc {
        val credentialSubject: SortedMap<String, Any?> = TreeMap<String, Any?>().apply {
            put("id", req.subjectId)
            req.attributes.forEach { (k, v) -> put(k, v) }
        }
        val credentialStatus: Map<String, Any?> = mapOf(
            "type" to "BitstringStatusListEntry",
            "statusListIndex" to req.statusListIndex,
            "statusListCredential" to req.statusListUri,
        )

        // Build the VC body without the proof first — that's what we sign.
        val body: Map<String, Any?> = linkedMapOf(
            "@context" to listOf(
                "https://www.w3.org/ns/credentials/v2",
                "https://studentzk.eu/contexts/student/v1",
            ),
            "type" to listOf("VerifiableCredential", "StudentCredential"),
            "issuer" to req.issuerUri,
            "validFrom" to req.validFrom.toString(),
            "validUntil" to req.validUntil.toString(),
            "credentialSubject" to credentialSubject,
            "credentialStatus" to credentialStatus,
        )

        val messages = canonicalize(body)
        val messageBytes = messages.map { it.toByteArray(Charsets.UTF_8) }
        val signature = BbsCrypto.sign(messageBytes, keyService.keypair.secretKey)

        val proof: Map<String, Any?> = linkedMapOf(
            "type" to "DataIntegrityProof",
            "cryptosuite" to "studentzk-bbs-2023",
            "canonicalization" to "studentzk-canonical-v1",
            "created" to Instant.now().toString(),
            "verificationMethod" to "${req.publicBaseUrl}/.well-known/studentzkp-bbs-key.json#${keyService.kid}",
            "proofPurpose" to "assertionMethod",
            "messages" to messages,
            "proofValue" to b64url(signature),
        )

        val signed = linkedMapOf<String, Any?>().apply {
            putAll(body)
            put("proof", proof)
        }
        return IssuedBbsVc(signed, messages, signature)
    }

    /**
     * `studentzk-canonical-v1`: depth-first traversal, leaves only.
     *
     * Each emitted message is `"<dot.path>=<canonical_json>"` where
     * `canonical_json` is RFC 8785-style (ordered keys, no whitespace) for
     * objects, and primitive form (`true`/`123`/`"str"`) for leaves.
     *
     * Lists are intentionally serialized whole rather than indexed because none
     * of our credential attributes are mutable lists — `@context` and `type`
     * arrays are issuer-fixed and verifiers re-derive from the schema. If this
     * changes, extend traversal here AND the verifier in lockstep.
     */
    fun canonicalize(body: Map<String, Any?>): List<String> {
        val out = mutableListOf<String>()
        traverse("", body, out)
        return out
    }

    private fun traverse(prefix: String, value: Any?, out: MutableList<String>) {
        when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val sorted = (value as Map<String, Any?>).toSortedMap()
                for ((k, v) in sorted) {
                    val path = if (prefix.isEmpty()) k else "$prefix.$k"
                    if (v is Map<*, *>) {
                        traverse(path, v, out)
                    } else {
                        out += "$path=${canonicalJson(v)}"
                    }
                }
            }
            else -> {
                out += "$prefix=${canonicalJson(value)}"
            }
        }
    }

    private fun canonicalJson(value: Any?): String = canonicalMapper.writeValueAsString(value)

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
