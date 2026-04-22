package hr.fer.studentzkp.controller

import hr.fer.studentzkp.repository.CredentialRepository
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

// Dev shortcut for the "can the verifier see revocations?" test path.
// Flips `revoked=true` on the credential row; the next /statuslist/*.json fetch
// will have the corresponding bit set. Disabled under the "prod" profile.
@RestController
@Profile("!prod")
class DevRevocationController(
    private val credentials: CredentialRepository,
) {

    @PostMapping("/dev/credential/{credentialId}/revoke")
    fun revoke(@PathVariable credentialId: String): Map<String, Any> {
        val uuid = runCatching { UUID.fromString(credentialId) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a UUID: $credentialId")
        }
        val credential = credentials.findById(uuid).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No credential $uuid")
        }
        credential.revoked = true
        credential.revokedAt = OffsetDateTime.now()
        credentials.save(credential)
        return mapOf(
            "credentialId" to credential.id.toString(),
            "statusIdx" to credential.statusIdx,
            "revoked" to true,
        )
    }
}
