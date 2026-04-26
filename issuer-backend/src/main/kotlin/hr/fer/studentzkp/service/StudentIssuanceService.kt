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
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

@Service
class StudentIssuanceService(
    private val studentRepo: StudentRepository,
    private val credentialTypeRepo: CredentialTypeRepository,
    private val credentialRepo: CredentialRepository,
    private val sdJwtVc: SdJwtVcService,
    private val statusListService: StatusListService,
    @Value("\${studentzkp.issuer.id}") private val issuerUri: String,
) {
    // Used to call entityManager.persist() directly. JpaRepository.save() picks
    // merge() vs persist() via isNew() — and our Credential has a pre-populated
    // UUID id and no @Version field, so save() guesses "detached" and tries
    // UPDATE. persist() unambiguously inserts.
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    companion object {
        const val STUDENT_TYPE_URI = "https://studentzk.eu/types/student/v1"
    }

    data class IssuedCredentialDto(
        val credentialId: String,
        val statusIdx: Int,
        val sdJwt: String,
        val disclosures: List<DisclosureDto>,
    )

    data class DisclosureDto(val name: String, val value: Any?, val disclosureB64: String)

    @Transactional
    fun issueForStudent(studentId: String, holderCnfJwk: Map<String, Any>?): IssuedCredentialDto {
        val student = studentRepo.findByStudentId(studentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown student: $studentId")
        val type = credentialTypeRepo.findByUri(STUDENT_TYPE_URI)
            ?: throw IllegalStateException("Student credential type not seeded — check Flyway migrations")

        val today = LocalDate.now()
        val validUntil = today.plusDays(type.defaultValidityDays.toLong())
        val age = Period.between(student.dateOfBirth, today).years

        // Atomic allocation via the credential_status_idx_seq Postgres sequence
        // (Flyway V4). Concurrent issuances are guaranteed distinct bits.
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
            "student_id" to student.studentId,
            "university_id" to student.universityId,
            "given_name_hash" to sha256Hex(student.givenName),
            "family_name_hash" to sha256Hex(student.familyName),
            "is_student" to student.isActive,
            "age_equal_or_over" to mapOf("18" to (age >= 18)),
        )

        // For a dev-issued credential without a real holder DID, we synthesize
        // an opaque subject. Phase 2 replaces this with did:jwk from the holder's
        // StrongBox-generated public key.
        val subjectDid = holderCnfJwk?.let { "did:jwk:synthetic-${student.id}" }
            ?: "urn:dev:${student.studentId}"

        val issued = sdJwtVc.issue(
            SdJwtVcService.Request(
                issuer = issuerUri,
                vct = STUDENT_TYPE_URI,
                subjectDid = subjectDid,
                cnfJwk = holderCnfJwk,
                validitySeconds = type.defaultValidityDays * 86_400L,
                alwaysDisclosed = alwaysDisclosed,
                selectivelyDisclosable = selective,
            ),
        )

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
        )
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
