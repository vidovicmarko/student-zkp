package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.IssuedCredential
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IssuedCredentialRepository : JpaRepository<IssuedCredential, UUID> {
    fun findByStudentId(studentId: UUID): List<IssuedCredential>
}
