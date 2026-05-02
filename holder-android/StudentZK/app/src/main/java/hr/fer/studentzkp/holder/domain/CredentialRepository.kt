package hr.fer.studentzkp.holder.domain

import android.util.Base64
import hr.fer.studentzkp.holder.crypto.BbsCrypto
import hr.fer.studentzkp.holder.data.local.CredentialStore
import hr.fer.studentzkp.holder.data.model.StoredCredential
import hr.fer.studentzkp.holder.data.model.VerificationResult
import hr.fer.studentzkp.holder.data.network.IssuerApiClient
import hr.fer.studentzkp.holder.util.BbsVcUtils
import hr.fer.studentzkp.holder.util.HolderKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.Inflater

class CredentialRepository(private val store: CredentialStore) {

    private fun apiClient() = IssuerApiClient(store.getServerUrl())

    // ─── Wallet storage ────────────────────────────────────────────────────────

    fun getAllCredentials(): List<StoredCredential> = store.loadAll()

    fun deleteCredential(id: String) = store.delete(id)

    // ─── Issuance ──────────────────────────────────────────────────────────────

    /**
     * Issue a BBS+ credential via the dev shortcut endpoint.
     * The issuer returns a W3C VCDM 2.0 BBS VC in the `bbsVc` field.
     */
    suspend fun devIssueAndStore(studentId: String): Result<StoredCredential> {
        val cnfJwk = runCatching { HolderKeyManager.publicKeyJwk() }.getOrNull()
        val result = apiClient().devIssueCredential(studentId, cnfJwk)
        return result.map { resp ->
            val data = BbsVcUtils.parse(resp.bbsVcJson)
            val info = BbsVcUtils.extractStudentInfo(data)
            StoredCredential(
                id = resp.credentialId,
                bbsVcJson = resp.bbsVcJson,
                studentId = studentId,
                issuedAt = System.currentTimeMillis(),
                validUntil = info.validUntil,
                universityId = info.universityId,
                isStudent = info.isStudent,
                statusIdx = resp.statusIdx,
                statusListUri = info.statusListUri,
            ).also { store.save(it) }
        }
    }

    // ─── Presentation (BBS+ selective disclosure) ─────────────────────────────

    /**
     * Build a BBS+ selective-disclosure presentation for [credentialId].
     *
     * The holder derives a BBS proof that reveals only the selected attributes.
     * Returns the full BBS VC JSON — for now the presentation is the credential
     * itself (verifier does full-disclosure verify). A future version will
     * produce a derived proof with only the selected messages.
     */
    fun buildPresentation(
        credentialId: String,
        nonce: String,
        audience: String,
        selectedDisclosureNames: Set<String>? = null,
    ): Result<String> = runCatching {
        val cred = store.loadAll().firstOrNull { it.id == credentialId }
            ?: error("Credential not found: $credentialId")
        // For now, share the full BBS VC JSON as the presentation.
        // Selective-disclosure proof derivation will be added when the holder
        // needs to prove only a subset of attributes.
        cred.bbsVcJson
    }

    // ─── Verification ──────────────────────────────────────────────────────────

    /**
     * Verify a scanned BBS+ credential JSON.
     * 1. Parse the W3C VCDM 2.0 credential.
     * 2. Fetch the issuer's BBS public key.
     * 3. Derive a full-disclosure proof and verify it against the public key.
     * 4. Check revocation via status list (fail-soft).
     */
    suspend fun verifyBbsCredential(credentialJson: String): VerificationResult =
        withContext(Dispatchers.IO) {
            val data = try {
                BbsVcUtils.parse(credentialJson)
            } catch (e: Exception) {
                return@withContext VerificationResult.Invalid(
                    "Failed to parse BBS credential: ${e.message}",
                )
            }

            val info = BbsVcUtils.extractStudentInfo(data)

            // Fetch issuer's BBS public key.
            // Try the URL embedded in the credential first; fall back to the
            // server URL configured in the app (handles localhost-issued creds
            // being verified from a different device).
            val issuerBaseUrl = BbsVcUtils.extractIssuerBaseUrl(data)
            val pubKeyBytes = try {
                val candidates = listOfNotNull(issuerBaseUrl, store.getServerUrl()).distinct()
                if (candidates.isEmpty()) {
                    return@withContext VerificationResult.Invalid(
                        "Cannot determine issuer URL from credential or app settings",
                    )
                }
                var lastError: Exception? = null
                var resp: hr.fer.studentzkp.holder.data.network.BbsPublicKeyResponse? = null
                for (url in candidates) {
                    resp = try {
                        apiClient(url).getBbsPublicKey().getOrThrow()
                    } catch (e: Exception) {
                        lastError = e
                        null
                    }
                    if (resp != null) break
                }
                if (resp == null) throw lastError ?: Exception("No issuer URL available")
                Base64.decode(
                    resp.publicKey,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            } catch (e: Exception) {
                return@withContext VerificationResult.Invalid(
                    "Failed to fetch issuer BBS public key: ${e.message}",
                )
            }

            // Verify the BBS+ signature by deriving a full-disclosure proof and
            // checking it. This proves the credential was signed by the issuer's
            // secret key without requiring a separate verify-signature FFI call.
            val messageBytes = data.messages.map { it.toByteArray(Charsets.UTF_8) }
            val allIndices = messageBytes.indices.toList()
            val nonce = "verify".toByteArray()

            try {
                val proofBytes = BbsCrypto.deriveProof(
                    signature = data.proofValueBytes,
                    messages = messageBytes,
                    disclosedIndices = allIndices,
                    nonce = nonce,
                )
                val valid = BbsCrypto.verifyProof(
                    proof = proofBytes,
                    publicKey = pubKeyBytes,
                    disclosedIndices = allIndices,
                    disclosedMessages = messageBytes,
                    totalMessageCount = messageBytes.size,
                    nonce = nonce,
                )
                if (!valid) {
                    return@withContext VerificationResult.Invalid(
                        "BBS+ signature verification failed: proof did not verify against the issuer's public key.",
                    )
                }
            } catch (e: Exception) {
                return@withContext VerificationResult.Invalid(
                    "BBS+ crypto verification error: ${e.message}",
                )
            }

            // Revocation check (fail-soft)
            var statusOk: Boolean? = null
            var revoked = false
            if (info.statusIdx >= 0 && !info.statusListUri.isNullOrEmpty()) {
                runCatching {
                    val sl = apiClient().getStatusList().getOrThrow()
                    val inflated = inflate(sl.lst)
                    if (isRevoked(info.statusIdx, inflated)) {
                        revoked = true
                    } else {
                        statusOk = true
                    }
                }
            }

            if (revoked) {
                VerificationResult.Revoked(info.statusIdx)
            } else {
                VerificationResult.Valid(
                    isStudent = info.isStudent,
                    validUntil = info.validUntil,
                    universityId = info.universityId,
                    statusOk = statusOk,
                    ageOver18 = info.ageOver18,
                )
            }
        }

    // ─── Settings ─────────────────────────────────────────────────────────────

    fun getServerUrl(): String = store.getServerUrl()
    fun saveServerUrl(url: String) = store.saveServerUrl(url)

    suspend fun checkServerHealth(): Boolean =
        apiClient().checkHealth().getOrElse { false }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun apiClient(baseUrl: String) = IssuerApiClient(baseUrl)

    /** Check revocation bit in an inflated bitstring. */
    private fun isRevoked(statusIdx: Int, inflatedBits: ByteArray): Boolean {
        if (statusIdx < 0) return false
        val byteIdx = statusIdx / 8
        val bitIdx = statusIdx % 8
        if (byteIdx >= inflatedBits.size) return false
        return (inflatedBits[byteIdx].toInt() and (1 shl bitIdx)) != 0
    }

    /**
     * Inflate a base64url-deflate compressed bitstring from the IETF Token Status List.
     */
    private fun inflate(b64: String): ByteArray {
        val compressed = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val inflater = Inflater(false)
        inflater.setInput(compressed)
        val buf = ByteArray(131072 / 8)
        val len = inflater.inflate(buf)
        inflater.end()
        return buf.copyOf(len)
    }
}
