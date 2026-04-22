package hr.fer.studentzkp.repository

import hr.fer.studentzkp.model.Issuer
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IssuerRepository : JpaRepository<Issuer, UUID> {
    fun findByUri(uri: String): Issuer?
}
