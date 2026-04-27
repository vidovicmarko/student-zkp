package hr.fer.studentzkp.holder.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class StoredCredential(
    val id: String,
    val sdJwt: String,
    val studentId: String,
    val issuedAt: Long,
    val validUntil: String?,
    val credentialType: String = "UniversityStudent",
    val universityId: String? = null,
    val isStudent: Boolean = true,
    val statusIdx: Int = -1,
    val statusListUri: String? = null,
)
