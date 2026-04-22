package hr.fer.studentzkp.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "credential_type")
class CredentialType(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    val uri: String,

    @Column(name = "display_name", nullable = false)
    val displayName: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
    val schemaJson: Map<String, Any>,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disclosure_policy", nullable = false, columnDefinition = "jsonb")
    val disclosurePolicy: Map<String, Any>,

    @Column(name = "default_validity_days", nullable = false)
    val defaultValidityDays: Int = 365,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issuer_id", nullable = false)
    val issuer: Issuer,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
