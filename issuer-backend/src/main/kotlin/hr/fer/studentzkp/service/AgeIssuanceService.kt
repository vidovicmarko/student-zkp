package hr.fer.studentzkp.service

import hr.fer.studentzkp.model.Credential
import hr.fer.studentzkp.repository.CredentialRepository
import hr.fer.studentzkp.repository.CredentialTypeRepository
import hr.fer.studentzkp.repository.StudentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

@Service
class AgeIssuanceService(
    private val studentRepo: StudentRepository,
    private val credentialTypeRepo: CredentialTypeRepository,
    private val credentialRepo: CredentialRepository,
    private val sdJwtVc: SdJwtVcService,
    private val bbsVc: BbsVcService,
    private val statusListService: StatusListService,
    private val registryService: StudentRegistryService,
    @Value("\${studentzkp.issuer.id}") private val issuerUri: String,
    @Value("\${studentzkp.issuer.publicBaseUrl}") private val publicBaseUrl: String,
) {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    companion object {
        const val AGE_TYPE_URI = "https://studentzk.eu/types/age/v1"
    }

    data class IssuedCredentialDto(
        val credentialId: String,
        val statusIdx: Int,
        val sdJwt: String,
        val disclosures: List<DisclosureDto>,
        val bbsVc: Map<String, Any?>?,
    )

    data class DisclosureDto(val name: String, val value: Any?, val disclosureB64: String)

    @Transactional
    fun issueForStudent(studentId: String, holderCnfJwk: Map<String, Any>?): IssuedCredentialDto {
        // Validate JMBAG format (10 digits, Croatian student ID).
        if (!isValidJmbag(studentId)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid JMBAG format: must be 10 digits (e.g., 0036123456)",
            )
        }

        // Verify the JMBAG against the university's student registry (LDAP, API, etc).
        if (!registryService.isValidStudent(studentId)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "JMBAG is not valid in the university student registry",
            )
        }

        val student = studentRepo.findByStudentId(studentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown student: $studentId")
        val type = credentialTypeRepo.findByUri(AGE_TYPE_URI)
            ?: throw IllegalStateException("Age credential type not seeded — check Flyway migrations")

        val today = LocalDate.now()
        val validUntil = today.plusDays(type.defaultValidityDays.toLong())
        val age = Period.between(student.dateOfBirth, today).years

        val statusIdx = credentialRepo.nextStatusIdx().toInt()

        val alwaysDisclosed: Map<String, Any?> = mapOf(
            "valid_until" to validUntil.toString(),
            "status" to mapOf(
                "status_list" to mapOf(
                    "idx" to statusIdx,
                    "uri" to statusListService.uri,
                ),
            ),
        )
        val selective: Map<String, Any?> = mapOf(
            "age_equal_or_over" to mapOf("18" to (age >= 18)),
        )

        val subjectDid = holderCnfJwk?.let { "did:jwk:synthetic-${student.id}" }
            ?: "urn:dev:${student.studentId}"

        val issued = sdJwtVc.issue(
            SdJwtVcService.Request(
                issuer = issuerUri,
                vct = AGE_TYPE_URI,
                subjectDid = subjectDid,
                cnfJwk = holderCnfJwk,
                validitySeconds = type.defaultValidityDays * 86_400L,
                alwaysDisclosed = alwaysDisclosed,
                selectivelyDisclosable = selective,
            ),
        )

        val bbsCredential: Map<String, Any?>? = runCatching {
            bbsVc.issue(
                BbsVcService.Request(
                    issuerUri = issuerUri,
                    publicBaseUrl = publicBaseUrl,
                    subjectId = subjectDid,
                    statusListUri = statusListService.uri,
                    statusListIndex = statusIdx,
                    validFrom = today.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant(),
                    validUntil = validUntil.atStartOfDay().atOffset(ZoneOffset.UTC).toInstant(),
                    attributes = selective,
                ),
            ).credential
        }.getOrNull()

        val saved = Credential(
            type = type,
            subjectDid = subjectDid,
            attributes = (alwaysDisclosed + selective).filterValues { it != null }
                .mapValues { it.value as Any },
            cnfKeyJwk = holderCnfJwk,
            statusIdx = statusIdx,
            validUntil = validUntil.atStartOfDay().atOffset(ZoneOffset.UTC),
        ).also(entityManager::persist)

        return IssuedCredentialDto(
            credentialId = saved.id.toString(),
            statusIdx = statusIdx,
            sdJwt = issued.compact,
            disclosures = issued.disclosures.map {
                DisclosureDto(it.name, it.value, it.disclosureB64)
            },
            bbsVc = bbsCredential,
        )
    }

    private fun isValidJmbag(studentId: String): Boolean {
        // JMBAG (Croatian student ID) format: exactly 10 digits.
        return studentId.matches(Regex("^\\d{10}$"))
    }
}
