package hr.fer.studentzkp.service

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

// ES256 issuer key. Persisted as a JWK on disk so credentials issued before a
// restart still verify after one. The on-disk format is a single JWK JSON object
// (private key included). Delete the file to force a fresh keypair on next boot.
//
// `studentzkp.issuer.keyPath` is the path to the JWK file. If empty (or the path
// is unwritable), the service falls back to an ephemeral in-memory key — fine
// for unit tests, NOT for any flow whose credentials must outlive the JVM.
//
// For real production: replace this with a KMS / HSM-backed signer. The JWK on
// disk is a single-machine convenience, not a security boundary.
@Service
class IssuerKeyService(
    @Value("\${studentzkp.issuer.keyPath:}") private val keyPath: String,
) {

    private val log = LoggerFactory.getLogger(IssuerKeyService::class.java)

    private val key: ECKey = loadOrGenerate()

    val signingAlg: JWSAlgorithm = JWSAlgorithm.ES256
    val signingKid: String = key.keyID

    fun signer(): ECDSASigner = ECDSASigner(key)

    fun publicJwkSet(): Map<String, Any> =
        JWKSet(key.toPublicJWK()).toJSONObject(true /* publicOnly */)

    private fun loadOrGenerate(): ECKey {
        if (keyPath.isBlank()) {
            log.warn(
                "studentzkp.issuer.keyPath is empty — generating an ephemeral ES256 key. " +
                    "Credentials issued in this run will fail verification after a restart.",
            )
            return generateNew()
        }
        val path = Path.of(keyPath)
        if (Files.exists(path)) {
            return runCatching {
                val parsed = JWK.parse(Files.readString(path))
                require(parsed is ECKey) { "key file is not an EC JWK" }
                require(parsed.curve == Curve.P_256) { "key file is not on curve P-256" }
                require(parsed.isPrivate) { "key file does not contain a private key" }
                log.info("Loaded persisted issuer key kid={} from {}", parsed.keyID, path)
                parsed
            }.getOrElse { e ->
                throw IllegalStateException(
                    "Issuer key at $path is unreadable or malformed. Delete it to regenerate, " +
                        "or restore from backup. Cause: ${e.message}",
                    e,
                )
            }
        }
        val fresh = generateNew()
        runCatching {
            Files.createDirectories(path.parent ?: Path.of("."))
            Files.writeString(
                path,
                fresh.toJSONString(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.setPosixFilePermissions(
                    path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: UnsupportedOperationException) {
                // Windows — ACLs not POSIX. The key file inherits parent ACLs.
            }
            log.info("Generated new issuer key kid={} at {}", fresh.keyID, path)
        }.onFailure { e ->
            log.warn(
                "Failed to persist issuer key to {} ({}). Continuing with in-memory key — " +
                    "credentials issued this run will not verify after a restart.",
                path,
                e.message,
            )
        }
        return fresh
    }

    private fun generateNew(): ECKey =
        ECKeyGenerator(Curve.P_256)
            .keyID(UUID.randomUUID().toString())
            .algorithm(JWSAlgorithm.ES256)
            .keyUse(KeyUse.SIGNATURE)
            .generate()
}
