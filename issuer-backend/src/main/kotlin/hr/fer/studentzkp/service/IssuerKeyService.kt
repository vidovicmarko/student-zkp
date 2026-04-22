package hr.fer.studentzkp.service

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.springframework.stereotype.Service
import java.util.UUID

// Phase 1 shortcut: generate an ES256 issuer key in memory at boot.
// Credentials issued against this key verify only until the next restart —
// acceptable for a demo, NOT production. Phase 2 persists the key (HSM/KMS).
@Service
class IssuerKeyService {

    private val key: ECKey = ECKeyGenerator(Curve.P_256)
        .keyID(UUID.randomUUID().toString())
        .algorithm(JWSAlgorithm.ES256)
        .keyUse(KeyUse.SIGNATURE)
        .generate()

    val signingAlg: JWSAlgorithm = JWSAlgorithm.ES256
    val signingKid: String = key.keyID

    fun signer(): ECDSASigner = ECDSASigner(key)

    fun publicJwkSet(): Map<String, Any> =
        JWKSet(key.toPublicJWK()).toJSONObject(true /* publicOnly */)
}
