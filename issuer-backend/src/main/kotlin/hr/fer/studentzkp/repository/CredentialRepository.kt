package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.Credential
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface CredentialRepository : JpaRepository<Credential, UUID> {
    fun findBySubjectDid(subjectDid: String): List<Credential>
    fun findByStatusIdx(statusIdx: Int): Credential?
    fun findAllByRevokedTrue(): List<Credential>

    // Atomic allocation of the next status-list bit. The sequence is created in
    // Flyway migration V4. Native query because Spring Data JPA has no portable
    // way to call a sequence directly.
    @Query(value = "SELECT nextval('credential_status_idx_seq')", nativeQuery = true)
    fun nextStatusIdx(): Long
}
