package hr.fer.studentzkp.service

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// OpenID for Verifiable Credential Issuance (OID4VCI 1.0) state machine.
// Spec: https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html
//
// Pre-authorized-code flow only — the flow EUDI Wallet uses today and the only
// one walt.id wallets exercise out of the box. Three steps:
//
//   1. createOffer(studentId)         → admin/issuer registers a pending offer.
//   2. redeemPreAuthCode(code)        → wallet exchanges the code for an
//                                       access_token + c_nonce (RFC 6749 §5.1
//                                       + OID4VCI §6.3).
//   3. issueCredential(token, proof)  → wallet sends a JWT proof of possession
//                                       binding its `cnf` JWK to our c_nonce;
//                                       we validate, then mint the SD-JWT-VC.
//
// State lives in two ConcurrentHashMaps. A restart wipes in-flight handshakes;
// that's expected — pre-auth codes are short-lived by design.
@Service
class Oid4VciService(
    private val issuanceService: StudentIssuanceService,
    @Value("\${studentzkp.issuer.id}") val issuerUri: String,
    @Value("\${studentzkp.issuer.publicBaseUrl}") val publicBaseUrl: String,
    @Value("\${studentzkp.oid4vci.offerTtlSeconds:300}") private val offerTtlSeconds: Long,
    @Value("\${studentzkp.oid4vci.tokenTtlSeconds:600}") private val tokenTtlSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(Oid4VciService::class.java)
    private val rng = SecureRandom()

    // Keyed by offer_id (lookup for the wallet fetching by reference).
    private val offersById = ConcurrentHashMap<String, PendingOffer>()
    // Keyed by pre-authorized_code (lookup for the wallet's /token call).
    private val offersByCode = ConcurrentHashMap<String, PendingOffer>()
    // Keyed by access_token (lookup for the wallet's /credential call).
    private val tokens = ConcurrentHashMap<String, IssuedToken>()

    data class PendingOffer(
        val offerId: String,
        val preAuthCode: String,
        val studentId: String,
        val expiresAt: Instant,
        @Volatile var consumed: Boolean = false,
    ) {
        fun expired(): Boolean = Instant.now().isAfter(expiresAt)
    }

    data class IssuedToken(
        val accessToken: String,
        val studentId: String,
        val cNonce: String,
        val expiresAt: Instant,
        @Volatile var consumed: Boolean = false,
    ) {
        fun expired(): Boolean = Instant.now().isAfter(expiresAt)
    }

    fun createOffer(studentId: String): PendingOffer {
        val offer = PendingOffer(
            offerId = randomToken(),
            preAuthCode = randomToken(),
            studentId = studentId,
            expiresAt = Instant.now().plusSeconds(offerTtlSeconds),
        )
        offersById[offer.offerId] = offer
        offersByCode[offer.preAuthCode] = offer
        return offer
    }

    fun getOfferById(offerId: String): PendingOffer? =
        offersById[offerId]?.takeIf { !it.expired() && !it.consumed }

    fun redeemPreAuthCode(code: String): IssuedToken {
        val offer = offersByCode[code]
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown pre-authorized_code")
        if (offer.consumed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Pre-authorized_code already redeemed")
        }
        if (offer.expired()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Pre-authorized_code expired")
        }
        offer.consumed = true
        val token = IssuedToken(
            accessToken = randomToken(),
            studentId = offer.studentId,
            cNonce = randomToken(),
            expiresAt = Instant.now().plusSeconds(tokenTtlSeconds),
        )
        tokens[token.accessToken] = token
        return token
    }

    fun issueCredential(authHeader: String?, proofJwt: String): StudentIssuanceService.IssuedCredentialDto {
        val token = parseBearer(authHeader)
        val issued = tokens[token]
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown access_token")
        if (issued.consumed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Access_token already used")
        }
        if (issued.expired()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access_token expired")
        }
        val cnfJwk = validateProof(proofJwt, issued.cNonce)
        issued.consumed = true
        log.info("Issuing credential for student={} bound to cnf jwk kty={}",
            issued.studentId, cnfJwk["kty"])
        return issuanceService.issueForStudent(issued.studentId, cnfJwk)
    }

    fun ttlSecondsRemaining(token: IssuedToken): Long =
        Duration.between(Instant.now(), token.expiresAt).seconds.coerceAtLeast(0)

    // OID4VCI §7.2.1 — proof of possession of the holder's `cnf` key.
    // The proof is a compact JWS with:
    //   header  : { typ: "openid4vci-proof+jwt", alg, jwk } | { kid } | { x5c }
    //   payload : { aud: <issuer>, iat, nonce: <c_nonce> }
    // We support the `jwk` form only — that's what walt.id and EUDI wallets emit
    // for first-time issuance. `kid` (DID URL) is Phase 3 once we have a DID resolver.
    private fun validateProof(proofJwt: String, expectedNonce: String): Map<String, Any> {
        val signed = try {
            SignedJWT.parse(proofJwt)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proof JWT is malformed: ${e.message}")
        }
        val header = signed.header
        if (header.type?.type != PROOF_JWT_TYP) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Proof JWT must use typ=$PROOF_JWT_TYP, got typ=${header.type?.type}",
            )
        }
        val jwk = header.jwk
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proof JWT missing jwk header (kid not yet supported)")
        val publicJwk = jwk.toPublicJWK()

        val verifier = when (publicJwk) {
            is ECKey -> ECDSAVerifier(publicJwk)
            is RSAKey -> RSASSAVerifier(publicJwk)
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported jwk kty=${publicJwk.keyType.value} (use EC or RSA)",
            )
        }
        if (!signed.verify(verifier)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proof signature did not verify against jwk in header")
        }

        val claims = signed.jwtClaimsSet
        val aud = claims.audience?.firstOrNull()
        if (aud != issuerUri) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Proof JWT aud must be \"$issuerUri\", got \"$aud\"",
            )
        }
        val nonce = claims.getStringClaim("nonce")
        if (nonce != expectedNonce) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proof JWT nonce does not match c_nonce")
        }
        val iat = claims.issueTime?.toInstant()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proof JWT missing iat")
        val skew = Duration.between(iat, Instant.now()).abs()
        if (skew > MAX_PROOF_SKEW) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Proof JWT iat too far from now (${skew.seconds}s, max ${MAX_PROOF_SKEW.seconds}s)",
            )
        }

        return jwkToMap(publicJwk)
    }

    private fun parseBearer(authHeader: String?): String {
        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ", ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Bearer Authorization header")
        }
        return authHeader.substring("Bearer ".length).trim()
    }

    private fun jwkToMap(jwk: JWK): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return jwk.toJSONObject() as Map<String, Any>
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(rng::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PRE_AUTH_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:pre-authorized_code"
        const val PROOF_JWT_TYP = "openid4vci-proof+jwt"
        private val MAX_PROOF_SKEW: Duration = Duration.ofMinutes(5)
    }
}
