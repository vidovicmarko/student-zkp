package hr.fer.studentzkp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Date

// Hand-rolled SD-JWT-VC issuer (draft-ietf-oauth-sd-jwt-vc) on top of
// nimbus-jose-jwt. Good enough for Phase 1; swap for walt.id / EUDI libs
// before shipping. Spec: https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/
//
// Wire format:   <issuer-JWT>~<disclosure_1>~<disclosure_2>~...~[<KB-JWT>]
// Each disclosure is:  base64url( JSON [ salt_b64url, claim_name, claim_value ] )
// Each hash is:        base64url( SHA-256 ( disclosure_bytes ) )   (hash of the
//                                   encoded disclosure string, NOT of the raw JSON)
@Service
class SdJwtVcService(
    private val issuerKeyService: IssuerKeyService,
    private val mapper: ObjectMapper,
) {
    private val rng = SecureRandom()

    data class Disclosure(
        val name: String,
        val value: Any?,
        val disclosureB64: String,
        val hashB64: String,
    )

    data class IssuedSdJwtVc(
        val compact: String,
        val disclosures: List<Disclosure>,
    )

    data class Request(
        val issuer: String,
        val vct: String,
        val subjectDid: String?,
        val cnfJwk: Map<String, Any>?,
        val validitySeconds: Long,
        val alwaysDisclosed: Map<String, Any?>,
        val selectivelyDisclosable: Map<String, Any?>,
    )

    fun issue(req: Request): IssuedSdJwtVc {
        val disclosures = req.selectivelyDisclosable.map { (k, v) -> makeDisclosure(k, v) }

        val now = Instant.now()
        val payloadBuilder = JWTClaimsSet.Builder()
            .issuer(req.issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(req.validitySeconds)))
            .claim("vct", req.vct)
            .claim("_sd", disclosures.map { it.hashB64 })
            .claim("_sd_alg", "sha-256")

        if (req.subjectDid != null) payloadBuilder.subject(req.subjectDid)
        if (req.cnfJwk != null) payloadBuilder.claim("cnf", mapOf("jwk" to req.cnfJwk))
        req.alwaysDisclosed.forEach { (k, v) -> payloadBuilder.claim(k, v) }

        val header = JWSHeader.Builder(issuerKeyService.signingAlg)
            .keyID(issuerKeyService.signingKid)
            .type(JOSEObjectType("vc+sd-jwt"))
            .build()

        val jwt = SignedJWT(header, payloadBuilder.build())
        jwt.sign(issuerKeyService.signer())

        val compact = buildString {
            append(jwt.serialize())
            disclosures.forEach { append('~').append(it.disclosureB64) }
            append('~')
        }

        return IssuedSdJwtVc(compact, disclosures)
    }

    private fun makeDisclosure(name: String, value: Any?): Disclosure {
        val salt = ByteArray(16).also(rng::nextBytes)
        val saltB64 = Base64URL.encode(salt).toString()
        val json = mapper.writeValueAsString(listOf(saltB64, name, value))
        val disclosureB64 = Base64URL.encode(json.toByteArray(Charsets.UTF_8)).toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(disclosureB64.toByteArray(Charsets.UTF_8))
        return Disclosure(
            name = name,
            value = value,
            disclosureB64 = disclosureB64,
            hashB64 = Base64URL.encode(digest).toString(),
        )
    }
}
