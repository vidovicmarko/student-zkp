package hr.fer.studentzkp.service

import hr.fer.studentzkp.model.IntegrityAssertion
import hr.fer.studentzkp.repository.IntegrityAssertionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.security.SecureRandom

// Production Play Integrity implementation. Calls Google's decodeIntegrityToken API.
// Active only when the service account JSON is configured (prod profile).
//
// NOTE: This implementation is a stub that stores verdicts in the database.
// To enable real Google Play Integrity verification, uncomment the google-api-client
// and google-api-services-playintegrity dependencies in build.gradle.kts and
// implement the full token decoding logic using Google's API client.
@Service
@Profile("prod")
class PlayIntegrityService(
    @Value("\${studentzkp.playIntegrity.projectNumber}") private val projectNumber: String,
    @Value("\${studentzkp.playIntegrity.serviceAccountJson}") private val serviceAccountJson: String,
    private val integrityRepo: IntegrityAssertionRepository,
) : IntegrityService {

    private val log = LoggerFactory.getLogger(PlayIntegrityService::class.java)
    private val rng = SecureRandom()

    override fun issueNonce(subjectDid: String): NonceChallenge {
        val nonce = ByteArray(32).also(rng::nextBytes)
        log.debug("Issued Play Integrity nonce for subject: {}", subjectDid)
        return NonceChallenge(nonce = nonce, expiresInSeconds = 120)
    }

    override fun verifyToken(token: String, requestHash: ByteArray): IntegrityVerdict {
        // In production, this would:
        // 1. Decode the token using Google's PlayIntegrity API
        // 2. Check deviceIntegrityVerdict (reject ROOTED, EMULATOR)
        // 3. Check appIntegrity.appRecognitionVerdict (require PLAYS_CERTIFIED)
        // 4. Check nonce for replay (via integrityRepo.findByNonce)
        // 5. Store result in IntegrityAssertion table
        //
        // For now, return a placeholder verdict.
        log.warn(
            "PlayIntegrityService: Google API not configured. Token would be verified against project: {}",
            projectNumber
        )
        return IntegrityVerdict(
            ok = false,
            reasons = listOf(
                "Play Integrity verification not configured. " +
                "Set PLAY_INTEGRITY_PROJECT_NUMBER and PLAY_INTEGRITY_SA_JSON environment variables " +
                "and uncomment google-api-services-playintegrity dependency in build.gradle.kts"
            ),
        )
    }
}
