package hr.fer.studentzkp.holder.data.network

data class DevCredentialResponse(
    val credentialId: String,
    val statusIdx: Int,
    val bbsVcJson: String,
)

data class CredentialOfferResponse(
    val offerId: String,
    val preAuthorizedCode: String,
    val credentialOfferUri: String,
    val deepLink: String,
    val expiresInSeconds: Int,
)

data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val cNonce: String,
    val cNonceExpiresIn: Int,
)

data class CredentialResponse(
    val credential: String,
    val credentialId: String,
)

data class StatusListResponse(
    val bits: Int,
    val lst: String,
)

data class HealthResponse(
    val status: String,
    val service: String,
)

data class BbsPublicKeyResponse(
    val kid: String,
    val publicKey: String,
)
