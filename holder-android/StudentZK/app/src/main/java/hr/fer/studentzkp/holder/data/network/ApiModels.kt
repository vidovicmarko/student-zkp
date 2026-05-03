package hr.fer.studentzkp.holder.data.network

data class DevCredentialResponse(
    val credentialId: String,
    val statusIdx: Int,
    val bbsVcJson: String,
)

data class StatusListResponse(
    val bits: Int,
    val lst: String,
)

data class BbsPublicKeyResponse(
    val kid: String,
    val publicKey: String,
)
