package hr.fer.studentzkp.model

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "issued_credentials")
class IssuedCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    val student: Student,

    @Column(name = "credential_type", nullable = false)
    val credentialType: String,

    @Column(name = "status_index", nullable = false)
    val statusIndex: Int,

    @Column(name = "issued_at", nullable = false)
    val issuedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at")
    val expiresAt: OffsetDateTime? = null,

    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false,

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null,
)
