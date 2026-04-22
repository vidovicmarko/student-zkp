package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.Credential
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CredentialRepository : JpaRepository<Credential, UUID> {
    fun findBySubjectDid(subjectDid: String): List<Credential>
    fun findByStatusIdx(statusIdx: Int): Credential?
    fun findAllByRevokedTrue(): List<Credential>
}
