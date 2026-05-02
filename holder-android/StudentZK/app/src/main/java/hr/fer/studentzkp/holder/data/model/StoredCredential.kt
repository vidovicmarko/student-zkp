package hr.fer.studentzkp.holder.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class StoredCredential(
    val id: String,
    val bbsVcJson: String,
    val studentId: String,
    val issuedAt: Long,
    val validUntil: String?,
    val credentialType: String = "StudentCredential",
    val universityId: String? = null,
    val isStudent: Boolean = true,
    val statusIdx: Int = -1,
    val statusListUri: String? = null,
)
