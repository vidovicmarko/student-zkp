package hr.fer.studentzkp.holder.domain

import android.util.Base64
import hr.fer.studentzkp.holder.data.local.CredentialStore
import hr.fer.studentzkp.holder.data.model.StoredCredential
import hr.fer.studentzkp.holder.data.model.VerificationResult
import hr.fer.studentzkp.holder.data.network.IssuerApiClient
import hr.fer.studentzkp.holder.util.SdJwtUtils
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
     * Issue a credential via the dev shortcut endpoint (no OID4VCI handshake).
     * Suitable for development / demo use.
     */
    suspend fun devIssueAndStore(studentId: String): Result<StoredCredential> {
        val result = apiClient().devIssueCredential(studentId)
        return result.map { resp ->
            val claims = SdJwtUtils.parse(resp.sdJwt)
            val info = SdJwtUtils.extractStudentInfo(claims)
            StoredCredential(
                id = resp.credentialId,
                sdJwt = resp.sdJwt,
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

    // ─── Verification ──────────────────────────────────────────────────────────

    /**
     * Verify a scanned SD-JWT-VC string.
     * 1. Parse and check disclosure hashes.
     * 2. Optionally check revocation via status list.
     */
    suspend fun verifySdJwt(compact: String): VerificationResult = withContext(Dispatchers.IO) {
        val claims = try {
            SdJwtUtils.parse(compact)
        } catch (e: Exception) {
            return@withContext VerificationResult.Invalid("Failed to parse SD-JWT-VC: ${e.message}")
        }

        val badHashes = SdJwtUtils.verifyDisclosureHashes(claims)
        if (badHashes.isNotEmpty()) {
            return@withContext VerificationResult.Invalid(
                "Disclosure hash mismatch for: ${badHashes.joinToString()}. Credential tampered."
            )
        }

        val info = SdJwtUtils.extractStudentInfo(claims)

        // Revocation check (fail-soft: if unreachable or parse error, treat as unknown)
        var statusOk: Boolean? = null
        var revoked = false
        if (info.statusIdx >= 0) {
            runCatching {
                val sl = apiClient().getStatusList().getOrThrow()
                val inflated = inflate(sl.lst)
                if (SdJwtUtils.isRevoked(info.statusIdx, inflated)) {
                    revoked = true
                } else {
                    statusOk = true
                }
            }
            // If status fetch or inflate fails, statusOk stays null (unknown)
        }

        if (revoked) {
            VerificationResult.Revoked(info.statusIdx)
        } else {
            VerificationResult.Valid(
                isStudent = info.isStudent,
                validUntil = info.validUntil,
                universityId = info.universityId,
                statusOk = statusOk,
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
     * Inflate a base64url-deflate compressed bitstring from the IETF Token Status List.
     * Backend uses Deflater() (zlib format with header), so nowrap must be false.
     */
    private fun inflate(b64: String): ByteArray {
        val compressed = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val inflater = Inflater(false) // nowrap=false: standard zlib format (matches backend Deflater())
        inflater.setInput(compressed)
        val buf = ByteArray(131072 / 8) // 131072 bits = 16384 bytes max
        val len = inflater.inflate(buf)
        inflater.end()
        return buf.copyOf(len)
    }
}
