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
@Table(name = "credential")
class Credential(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    val type: CredentialType,

    // Holder DID, derived from the StrongBox/TEE public key pinned via OID4VCI `cnf`.
    @Column(name = "subject_did", nullable = false)
    val subjectDid: String,

    // Attested attributes. Schema validated against type.schemaJson.
    // Never contains raw PII — only hashes + derived predicates (is_student, age_equal_or_over).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val attributes: Map<String, Any>,

    // Hash of the liveness-checked reference photo (final_plan §5.8 Layer 3).
    @Column(name = "photo_hash")
    val photoHash: ByteArray? = null,

    // Holder's hardware-bound public key (JWK). Pinned via OID4VCI `cnf` claim.
    // Nullable until Phase 2 (StrongBox-bound KB-JWT); dev issuance emits unbound creds.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cnf_key_jwk", columnDefinition = "jsonb")
    val cnfKeyJwk: Map<String, Any>? = null,

    // Bit position in the IETF Token Status List.
    @Column(name = "status_idx", nullable = false)
    val statusIdx: Int,

    @Column(name = "issued_at", nullable = false)
    val issuedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "valid_until", nullable = false)
    val validUntil: OffsetDateTime,

    @Column(nullable = false)
    var revoked: Boolean = false,

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null,
)
