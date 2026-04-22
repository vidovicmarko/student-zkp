package hr.fer.studentzkp.service

import com.nimbusds.jose.util.Base64URL
import hr.fer.studentzkp.repository.CredentialRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

// IETF Token Status List (draft-ietf-oauth-status-list).
// Bit i in the list corresponds to a credential with statusIdx = i.
// 0 = valid, 1 = revoked. Payload is deflate-compressed then base64url-encoded.
@Service
class StatusListService(
    private val credentialRepo: CredentialRepository,
    @Value("\${studentzkp.statusList.capacity:131072}") private val capacity: Int,
    @Value("\${studentzkp.statusList.uri}") val uri: String,
) {
    fun currentList(): Map<String, Any> {
        require(capacity % 8 == 0) { "capacity must be a multiple of 8" }
        val bytes = ByteArray(capacity / 8)
        credentialRepo.findAllByRevokedTrue().forEach { cred ->
            if (cred.statusIdx in 0 until capacity) {
                val byteIdx = cred.statusIdx ushr 3
                val bitIdx = cred.statusIdx and 0x7
                bytes[byteIdx] = (bytes[byteIdx].toInt() or (1 shl bitIdx)).toByte()
            }
        }
        return mapOf(
            "status_list" to mapOf(
                "bits" to 1,
                "lst" to Base64URL.encode(deflate(bytes)).toString(),
            ),
        )
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
