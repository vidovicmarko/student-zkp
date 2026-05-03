package hr.fer.studentzkp.holder.domain

import android.util.Base64
import hr.fer.studentzkp.holder.crypto.BbsCrypto
import hr.fer.studentzkp.holder.data.local.CredentialStore
import hr.fer.studentzkp.holder.data.model.StoredCredential
import hr.fer.studentzkp.holder.data.model.VerificationResult
import hr.fer.studentzkp.holder.data.network.IssuerApiClient
import hr.fer.studentzkp.holder.util.BbsVcUtils
import hr.fer.studentzkp.holder.util.HolderKeyManager
import hr.fer.studentzkp.holder.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.Inflater

class CredentialRepository(private val store: CredentialStore) {

    private fun apiClient(baseUrl: String = store.getServerUrl()) = IssuerApiClient(baseUrl)

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
                id = java.util.UUID.randomUUID().toString(),
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

    // ─── Selective Disclosure Presentation ──────────────────────────────────

    /**
     * Build a BBS+ selective-disclosure presentation.
     * Derives a BBS proof revealing only the messages at [disclosedIndices].
     * Returns a presentation JSON string ready for QR encoding.
     */
    fun buildSelectivePresentation(
        credentialId: String,
        disclosedIndices: List<Int>,
    ): Result<String> = runCatching {
        val cred = store.loadAll().firstOrNull { it.id == credentialId }
            ?: error("Credential not found: $credentialId")
        val data = BbsVcUtils.parse(cred.bbsVcJson)
        require(disclosedIndices.isNotEmpty()) { "Must disclose at least one attribute" }

        val allMessageBytes = data.messages.map { it.toByteArray(Charsets.UTF_8) }
        val nonce = "selective-disclosure".toByteArray()

        val derivedProof = BbsCrypto.deriveProof(
            signature = data.proofValueBytes,
            messages = allMessageBytes,
            disclosedIndices = disclosedIndices,
            nonce = nonce,
        )

        BbsVcUtils.buildSelectivePresentation(data, disclosedIndices, derivedProof)
    }

    // ─── Verification ──────────────────────────────────────────────────────────

    /**
     * Verify a scanned BBS+ credential or selective-disclosure presentation.
     */
    suspend fun verifyBbsCredential(credentialJson: String): VerificationResult =
        withContext(Dispatchers.IO) {
            if (BbsVcUtils.isSelectivePresentation(credentialJson)) {
                verifySelectivePresentation(credentialJson)
            } else {
                verifyFullCredential(credentialJson)
            }
        }

    /**
     * Verify a selective-disclosure BBS+ presentation.
     * The proof is already derived — just verify it against the public key.
     */
    private suspend fun verifySelectivePresentation(json: String): VerificationResult {
        val pres = try {
            BbsVcUtils.parseSelectivePresentation(json)
        } catch (e: Exception) {
            return VerificationResult.Invalid("Failed to parse presentation: ${e.message}")
        }

        val pubKeyBytes = try {
            fetchPublicKey(pres.verificationMethod)
        } catch (e: Exception) {
            return VerificationResult.Invalid("Failed to fetch issuer BBS public key: ${e.message}")
        }

        try {
            val valid = BbsCrypto.verifyProof(
                proof = pres.derivedProofBytes,
                publicKey = pubKeyBytes,
                disclosedIndices = pres.disclosedIndices,
                disclosedMessages = pres.disclosedMessages.map { it.toByteArray(Charsets.UTF_8) },
                totalMessageCount = pres.totalMessageCount,
                nonce = pres.nonce.toByteArray(Charsets.UTF_8),
            )
            if (!valid) {
                return VerificationResult.Invalid(
                    "BBS+ proof verification failed: derived proof did not verify.",
                )
            }
        } catch (e: Exception) {
            return VerificationResult.Invalid("BBS+ crypto verification error: ${e.message}")
        }

        // Extract student info from disclosed messages
        val disclosed = pres.disclosedMessages.associate { msg ->
            val key = msg.substringBefore("=")
            val value = msg.substringAfter("=")
            key to value
        }
        val isStudent = disclosed["credentialSubject.is_student"]?.toBooleanStrictOrNull()
        val universityId = disclosed["credentialSubject.university_id"]?.removeSurrounding("\"")
        val ageOver18Str = disclosed["credentialSubject.age_equal_or_over.18"]
        val ageOver18 = ageOver18Str?.toBooleanStrictOrNull()

        if (DateUtils.isExpired(pres.validUntil)) {
            return VerificationResult.Expired(pres.validUntil)
        }

        // Revocation check (fail-soft)
        var statusOk: Boolean? = null
        var revoked = false
        val statusIdx = pres.credentialStatus?.optInt("statusListIndex", -1) ?: -1
        val statusListUri = pres.credentialStatus?.optString("statusListCredential")
        if (statusIdx >= 0 && !statusListUri.isNullOrEmpty()) {
            runCatching {
                val sl = apiClient().getStatusList().getOrThrow()
                val inflated = inflate(sl.lst)
                if (isRevoked(statusIdx, inflated)) revoked = true else statusOk = true
            }
        }

        return if (revoked) {
            VerificationResult.Revoked(statusIdx)
        } else {
            VerificationResult.Valid(
                isStudent = isStudent,
                validUntil = pres.validUntil,
                universityId = universityId,
                statusOk = statusOk,
                ageOver18 = ageOver18,
            )
        }
    }

    /**
     * Verify a full BBS+ credential (original signature).
     */
    private suspend fun verifyFullCredential(credentialJson: String): VerificationResult {
        val data = try {
            BbsVcUtils.parse(credentialJson)
        } catch (e: Exception) {
            return VerificationResult.Invalid("Failed to parse BBS credential: ${e.message}")
        }

        val info = BbsVcUtils.extractStudentInfo(data)

        if (DateUtils.isExpired(info.validUntil)) {
            return VerificationResult.Expired(info.validUntil)
        }

        val pubKeyBytes = try {
            fetchPublicKey(data.proof.optString("verificationMethod"))
        } catch (e: Exception) {
            return VerificationResult.Invalid("Failed to fetch issuer BBS public key: ${e.message}")
        }

        // Verify by deriving a full-disclosure proof and checking it
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
                return VerificationResult.Invalid(
                    "BBS+ signature verification failed: proof did not verify against the issuer's public key.",
                )
            }
        } catch (e: Exception) {
            return VerificationResult.Invalid("BBS+ crypto verification error: ${e.message}")
        }

        // Revocation check (fail-soft)
        var statusOk: Boolean? = null
        var revoked = false
        if (info.statusIdx >= 0 && !info.statusListUri.isNullOrEmpty()) {
            runCatching {
                val sl = apiClient().getStatusList().getOrThrow()
                val inflated = inflate(sl.lst)
                if (isRevoked(info.statusIdx, inflated)) revoked = true else statusOk = true
            }
        }

        return if (revoked) {
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

    /**
     * Fetch the issuer's BBS public key from verification method URL.
     * Tries network first (embedded URL + configured server URL), caches by kid,
     * falls back to cached key for offline verification.
     */
    private suspend fun fetchPublicKey(verificationMethod: String?): ByteArray {
        val issuerBaseUrl = if (!verificationMethod.isNullOrEmpty()) {
            try {
                val url = java.net.URL(verificationMethod)
                buildString {
                    append(url.protocol); append("://"); append(url.host)
                    if (url.port > 0 && url.port != url.defaultPort) append(":${url.port}")
                }
            } catch (_: Exception) { null }
        } else null

        val kid = verificationMethod?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }

        val candidates = listOfNotNull(issuerBaseUrl, store.getServerUrl()).distinct()
        var lastError: Exception? = null
        var resp: hr.fer.studentzkp.holder.data.network.BbsPublicKeyResponse? = null
        for (url in candidates) {
            resp = try {
                apiClient(url).getBbsPublicKey().getOrThrow()
            } catch (e: Exception) {
                lastError = e; null
            }
            if (resp != null) break
        }
        if (resp != null) {
            val cacheKid = resp.kid.ifBlank { kid }
            if (!cacheKid.isNullOrBlank()) store.cacheIssuerKey(cacheKid, resp.publicKey)
            return Base64.decode(resp.publicKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        // Offline fallback
        val cached = kid?.let { store.getCachedIssuerKey(it) }
        if (cached != null) {
            return Base64.decode(cached, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        throw lastError ?: Exception("No issuer URL available and no cached key")
    }

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
