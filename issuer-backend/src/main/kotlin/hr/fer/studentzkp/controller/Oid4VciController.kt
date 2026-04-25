package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.Oid4VciService
import hr.fer.studentzkp.service.StudentIssuanceService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// OID4VCI public endpoints. Three steps in one place so the wire flow is easy
// to follow:
//   GET  /credential-offer/{offerId}   — wallet fetches the offer JSON
//                                        (delivered by reference via deep-link).
//   POST /token                        — wallet redeems the pre-authorized_code.
//   POST /credential                   — wallet presents proof + receives the
//                                        SD-JWT-VC.
//
// Spec: https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html
@RestController
class Oid4VciController(
    private val oid4Vci: Oid4VciService,
) {

    @GetMapping("/credential-offer/{offerId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getCredentialOffer(@PathVariable offerId: String): Map<String, Any> {
        val offer = oid4Vci.getOfferById(offerId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired credential offer")
        return mapOf(
            "credential_issuer" to oid4Vci.issuerUri,
            // Wallets advertise support per credential_configuration_id; we expose
            // a single config for the Student type. Phase 3 adds more types here.
            "credential_configuration_ids" to listOf(STUDENT_CONFIG_ID),
            "grants" to mapOf(
                Oid4VciService.PRE_AUTH_GRANT_TYPE to mapOf(
                    "pre-authorized_code" to offer.preAuthCode,
                ),
            ),
        )
    }

    // RFC 6749 §3.2 — application/x-www-form-urlencoded request.
    @PostMapping(
        "/token",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun token(
        @RequestParam("grant_type") grantType: String,
        @RequestParam("pre-authorized_code") preAuthCode: String,
    ): Map<String, Any> {
        if (grantType != Oid4VciService.PRE_AUTH_GRANT_TYPE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "unsupported_grant_type — only ${Oid4VciService.PRE_AUTH_GRANT_TYPE} is supported",
            )
        }
        val token = oid4Vci.redeemPreAuthCode(preAuthCode)
        val ttl = oid4Vci.ttlSecondsRemaining(token)
        return mapOf(
            "access_token" to token.accessToken,
            "token_type" to "Bearer",
            "expires_in" to ttl,
            "c_nonce" to token.cNonce,
            "c_nonce_expires_in" to ttl,
        )
    }

    @PostMapping(
        "/credential",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun credential(
        @RequestHeader(value = "Authorization", required = false) authHeader: String?,
        @RequestBody req: CredentialRequest,
    ): CredentialResponse {
        if (req.format != SD_JWT_VC_FORMAT) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported format \"${req.format}\". Phase 1.5 issues only \"$SD_JWT_VC_FORMAT\".",
            )
        }
        val proof = req.proof
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "proof is required")
        if (proof.proof_type != "jwt") {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported proof_type \"${proof.proof_type}\" — only \"jwt\" is supported.",
            )
        }
        val issued: StudentIssuanceService.IssuedCredentialDto =
            oid4Vci.issueCredential(authHeader, proof.jwt)
        return CredentialResponse(
            credential = issued.sdJwt,
            credentialId = issued.credentialId,
        )
    }

    data class CredentialRequest(val format: String, val proof: Proof?)
    data class Proof(val proof_type: String, val jwt: String)
    data class CredentialResponse(val credential: String, val credentialId: String)

    companion object {
        const val STUDENT_CONFIG_ID = "UniversityStudent"
        const val SD_JWT_VC_FORMAT = "vc+sd-jwt"
    }
}
