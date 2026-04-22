package hr.fer.studentzkp.service

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.security.SecureRandom

// Dev-only stub so the app boots without Play Integrity credentials.
// Replace with a real implementation (google-api-services-playintegrity)
// before Phase 1 issuance lands. Active for every profile EXCEPT "prod".
@Service
@Profile("!prod")
class StubIntegrityService : IntegrityService {

    private val rng = SecureRandom()

    override fun issueNonce(subjectDid: String): NonceChallenge {
        val nonce = ByteArray(32).also(rng::nextBytes)
        return NonceChallenge(nonce = nonce, expiresInSeconds = 120)
    }

    override fun verifyToken(token: String, requestHash: ByteArray): IntegrityVerdict =
        IntegrityVerdict(
            ok = true,
            reasons = listOf("stub-service: token not validated against Google; replace before Phase 1"),
        )
}
