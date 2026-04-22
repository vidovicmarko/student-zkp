package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.IntegrityAssertion
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IntegrityAssertionRepository : JpaRepository<IntegrityAssertion, UUID> {
    fun findByNonce(nonce: ByteArray): IntegrityAssertion?
}
