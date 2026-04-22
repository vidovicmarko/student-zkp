package hr.fer.studentzkp.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.util.UUID

// Play Integrity audit record (final_plan §5.8, Layer 2).
// One row per verified integrity token. The nonce is unique to prevent replay;
// request_hash binds the token to the KB-JWT payload it was generated for.
@Entity
@Table(name = "integrity_assertions")
class IntegrityAssertion(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "subject_did", nullable = false)
    val subjectDid: String,

    @Column(nullable = false, unique = true)
    val nonce: ByteArray,

    @Column(name = "request_hash", nullable = false)
    val requestHash: ByteArray,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verdict_json", nullable = false, columnDefinition = "jsonb")
    val verdictJson: Map<String, Any>,

    @Column(name = "verdict_ok", nullable = false)
    val verdictOk: Boolean,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
