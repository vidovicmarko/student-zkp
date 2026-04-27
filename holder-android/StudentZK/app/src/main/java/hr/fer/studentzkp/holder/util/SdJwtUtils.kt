package hr.fer.studentzkp.holder.util

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SdJwtClaims(
    val jwt: String,
    val payload: JSONObject,
    val disclosures: List<Disclosure>,
)

data class Disclosure(
    val salt: String,
    val name: String,
    val value: Any,
    val rawB64: String,
)

object SdJwtUtils {

    /**
     * Parse a compact SD-JWT-VC: `<jwt>~<disc1>~<disc2>~...~`
     */
    fun parse(compact: String): SdJwtClaims {
        val parts = compact.split("~")
        val jwt = parts[0]
        val disclosureB64s = parts.drop(1).filter { it.isNotEmpty() }

        val jwtParts = jwt.split(".")
        require(jwtParts.size >= 2) { "Malformed JWT: expected at least header.payload" }

        val payloadBytes = Base64.decode(
            jwtParts[1],
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val payload = JSONObject(String(payloadBytes, Charsets.UTF_8))

        val disclosures = disclosureB64s.map { b64 ->
            val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            Disclosure(
                salt = arr.getString(0),
                name = arr.getString(1),
                value = arr.get(2),
                rawB64 = b64,
            )
        }

        return SdJwtClaims(jwt, payload, disclosures)
    }

    /** Extract well-known fields from a parsed SD-JWT-VC */
    fun extractStudentInfo(claims: SdJwtClaims): StudentInfo {
        val payload = claims.payload
        val validUntil = payload.optString("valid_until").takeIf { it.isNotEmpty() }
        val statusIdx = payload.optJSONObject("status")
            ?.optJSONObject("status_list")
            ?.optInt("idx", -1) ?: -1
        val statusListUri = payload.optJSONObject("status")
            ?.optJSONObject("status_list")
            ?.optString("uri")

        var isStudent = false
        var universityId: String? = null
        var ageOver18: Boolean? = null

        for (d in claims.disclosures) {
            when (d.name) {
                "is_student" -> isStudent = d.value as? Boolean ?: false
                "university_id" -> universityId = d.value.toString()
                "age_equal_or_over" -> {
                    val obj = d.value as? org.json.JSONObject
                    ageOver18 = obj?.optBoolean("18")
                }
            }
        }

        return StudentInfo(
            isStudent = isStudent,
            validUntil = validUntil,
            universityId = universityId,
            statusIdx = statusIdx,
            statusListUri = statusListUri,
            ageOver18 = ageOver18,
        )
    }

    /**
     * Verify disclosure integrity: each disclosure hash must be in the `_sd` array.
     * Returns list of invalid disclosure names (empty = all good).
     */
    fun verifyDisclosureHashes(claims: SdJwtClaims): List<String> {
        val sd = claims.payload.optJSONArray("_sd") ?: return emptyList()
        val sdHashes = (0 until sd.length()).map { sd.getString(it) }.toSet()
        val bad = mutableListOf<String>()

        for (d in claims.disclosures) {
            val hash = sha256B64Url(d.rawB64)
            if (hash !in sdHashes) bad.add(d.name)
        }
        return bad
    }

    /**
     * SD-JWT-VC §4.3 KB-JWT sd_hash:
     *   b64url( sha256( "<jwt>~<disc1>~...~<discN>~" ) )
     * I.e. the entire compact form *except* the trailing KB-JWT segment.
     * Note the trailing "~" must be present in the hashed input.
     */
    fun computeSdHash(presentationWithoutKb: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(presentationWithoutKb.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    /**
     * Build the part of a presentation that gets hashed into the KB-JWT.
     * If `selectedDisclosureNames` is null, all disclosures are included.
     * Output format: <jwt>~<disc1>~...~<discN>~
     */
    fun buildPresentationBody(claims: SdJwtClaims, selectedDisclosureNames: Set<String>? = null): String {
        val included = if (selectedDisclosureNames == null) {
            claims.disclosures
        } else {
            claims.disclosures.filter { it.name in selectedDisclosureNames }
        }
        return buildString {
            append(claims.jwt)
            included.forEach { append('~').append(it.rawB64) }
            append('~')
        }
    }

    /** Check revocation bit in an inflated bitstring. */
    fun isRevoked(statusIdx: Int, inflatedBits: ByteArray): Boolean {
        if (statusIdx < 0) return false
        val byteIdx = statusIdx / 8
        val bitIdx = statusIdx % 8
        if (byteIdx >= inflatedBits.size) return false
        return (inflatedBits[byteIdx].toInt() and (1 shl bitIdx)) != 0
    }

    private fun sha256B64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

data class StudentInfo(
    val isStudent: Boolean,
    val validUntil: String?,
    val universityId: String?,
    val statusIdx: Int,
    val statusListUri: String?,
    val ageOver18: Boolean? = null,
)
