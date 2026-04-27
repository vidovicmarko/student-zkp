package hr.fer.studentzkp.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.fer.studentzkp.crypto.BbsCrypto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

// BBS+ issuer keypair on BLS12-381. Persisted next to the ES256 ECKey so
// credentials minted before a restart still verify after one. The on-disk
// format is a single JSON object with base64url-encoded ark-serialize bytes:
//
//   {"publicKey": "<b64url(pk)>", "secretKey": "<b64url(sk)>"}
//
// Mirrors the contract of IssuerKeyService: rotate by deleting the file. The
// kid is the SHA-256 of the public key (base64url-encoded), so a fresh keypair
// implies a fresh kid and old verifiers never silently accept an unrelated key.
//
// SECURITY: In production this gets replaced by an HSM/KMS. The on-disk JSON is
// a single-machine convenience.
@Service
class BbsIssuerKeyService(
    @Value("\${studentzkp.issuer.bbsKeyPath:}") private val keyPath: String,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(BbsIssuerKeyService::class.java)

    val keypair: BbsCrypto.Keypair = loadOrGenerate()

    /** Stable id derived from the public key bytes; rotates when the key does. */
    val kid: String = sha256B64Url(keypair.publicKey)

    /** Base64url-encoded ark-serialize compressed public key. */
    fun publicKeyB64Url(): String = b64url(keypair.publicKey)

    /**
     * Public-key advertisement document, served from
     * `/.well-known/studentzkp-bbs-key.json`. Verifiers fetch this to learn the
     * issuer's BBS+ pubkey + the algorithm parameters our `bbs-2023` variant uses.
     */
    fun publicKeyDocument(): Map<String, Any> = mapOf(
        "kid" to kid,
        "alg" to "BBS-2023",
        "curve" to "BLS12-381",
        "publicKeyEncoding" to "ark-serialize-compressed",
        "publicKey" to publicKeyB64Url(),
        "messageEncoding" to "studentzk-canonical-v1",
    )

    private fun loadOrGenerate(): BbsCrypto.Keypair {
        if (keyPath.isBlank()) {
            log.warn(
                "studentzkp.issuer.bbsKeyPath is empty — generating an ephemeral BBS+ key. " +
                    "BBS credentials minted in this run will fail verification after a restart.",
            )
            return BbsCrypto.keygen()
        }
        val path = Path.of(keyPath)
        if (Files.exists(path)) {
            return runCatching {
                val raw = Files.readString(path)
                val node = mapper.readTree(raw)
                val pk = b64urlDecode(node.get("publicKey").asText())
                val sk = b64urlDecode(node.get("secretKey").asText())
                require(pk.isNotEmpty() && sk.isNotEmpty()) { "empty pk/sk in BBS key file" }
                log.info("Loaded persisted BBS+ key from {}", path)
                BbsCrypto.Keypair(pk, sk)
            }.getOrElse { e ->
                throw IllegalStateException(
                    "BBS+ key at $path is unreadable or malformed. Delete it to regenerate, " +
                        "or restore from backup. Cause: ${e.message}",
                    e,
                )
            }
        }
        val fresh = BbsCrypto.keygen()
        runCatching {
            Files.createDirectories(path.parent ?: Path.of("."))
            val payload = mapOf(
                "publicKey" to b64url(fresh.publicKey),
                "secretKey" to b64url(fresh.secretKey),
            )
            Files.writeString(
                path,
                mapper.writeValueAsString(payload),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.setPosixFilePermissions(
                    path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: UnsupportedOperationException) {
                // Windows — inherits parent ACLs.
            }
            log.info("Generated new BBS+ issuer key at {}", path)
        }.onFailure { e ->
            log.warn(
                "Failed to persist BBS+ key to {} ({}). Continuing with in-memory keypair — " +
                    "BBS credentials issued this run will not verify after a restart.",
                path,
                e.message,
            )
        }
        return fresh
    }

    private fun sha256B64Url(bytes: ByteArray): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun b64urlDecode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)
}
