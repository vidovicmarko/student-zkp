package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.IntegrityService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

// Play Integrity endpoints (final_plan §5.8).
@RestController
@RequestMapping("/integrity")
class IntegrityController(
    private val integrityService: IntegrityService,
) {

    @PostMapping("/nonce")
    fun nonce(@RequestBody req: NonceRequest): NonceResponse {
        val challenge = integrityService.issueNonce(req.subjectDid)
        return NonceResponse(
            nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge.nonce),
            expiresIn = challenge.expiresInSeconds,
        )
    }

    @PostMapping("/verify")
    fun verify(@RequestBody req: VerifyRequest): VerifyResponse {
        val requestHash = Base64.getUrlDecoder().decode(req.requestHash)
        val verdict = integrityService.verifyToken(req.token, requestHash)
        return VerifyResponse(ok = verdict.ok, reasons = verdict.reasons)
    }
}

data class NonceRequest(val subjectDid: String)
data class NonceResponse(val nonce: String, val expiresIn: Long)

data class VerifyRequest(val token: String, val requestHash: String)
data class VerifyResponse(val ok: Boolean, val reasons: List<String>)
