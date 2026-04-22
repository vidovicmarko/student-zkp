package hr.fer.studentzkp.service

// Play Integrity gateway (final_plan §5.8, Layer 2).
// The issuer calls verifyToken before issuing; the holder attaches a fresh
// integrity token to every OID4VP presentation. Tokens are decrypted and
// verified via Google's decodeIntegrityToken endpoint — NEVER on the client.
interface IntegrityService {

    // Issues a fresh server-generated nonce for a classic Play Integrity request.
    // The nonce is single-use and bound to the forthcoming request_hash.
    fun issueNonce(subjectDid: String): NonceChallenge =
        TODO("Phase 0/1 — wire google-api-services-playintegrity")

    // Verifies an integrity token against Google's servers, checks the verdict,
    // and records an IntegrityAssertion row.
    fun verifyToken(token: String, requestHash: ByteArray): IntegrityVerdict =
        TODO("Phase 0/1 — wire google-api-services-playintegrity")
}

data class NonceChallenge(
    val nonce: ByteArray,
    val expiresInSeconds: Long,
)

data class IntegrityVerdict(
    val ok: Boolean,
    val reasons: List<String>,
)
