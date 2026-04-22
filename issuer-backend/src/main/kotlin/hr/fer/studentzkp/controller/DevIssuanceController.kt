package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.StudentIssuanceService
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// Developer shortcut: issue an SD-JWT-VC for a student directly, skipping the
// full OID4VCI pre-authorized-code dance. Disabled under the "prod" profile.
// Real holder wallets will go through /credential-offer + /token + /credential,
// which is Phase 1.5 work.
@RestController
@Profile("!prod")
class DevIssuanceController(
    private val issuanceService: StudentIssuanceService,
) {

    @PostMapping("/dev/credential/{studentId}")
    fun issueForStudent(
        @PathVariable studentId: String,
        @RequestBody(required = false) body: IssueRequest?,
    ): StudentIssuanceService.IssuedCredentialDto =
        issuanceService.issueForStudent(studentId, body?.cnfJwk)

    data class IssueRequest(val cnfJwk: Map<String, Any>?)
}
