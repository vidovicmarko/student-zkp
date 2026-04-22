package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.CredentialType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CredentialTypeRepository : JpaRepository<CredentialType, UUID> {
    fun findByUri(uri: String): CredentialType?
}
