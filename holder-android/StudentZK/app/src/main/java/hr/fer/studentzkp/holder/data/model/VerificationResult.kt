package hr.fer.studentzkp.holder.data.model

import androidx.compose.runtime.Immutable

@Immutable
sealed class VerificationResult {
    @Immutable
    data class Valid(
        val isStudent: Boolean? = null,
        val validUntil: String?,
        val universityId: String?,
        val statusOk: Boolean?,
        val ageOver18: Boolean? = null,
    ) : VerificationResult()

    @Immutable
    data class Expired(
        val validUntil: String?,
    ) : VerificationResult()

    @Immutable
    data class Invalid(val reason: String) : VerificationResult()

    @Immutable
    data class Revoked(val statusIdx: Int) : VerificationResult()
}
