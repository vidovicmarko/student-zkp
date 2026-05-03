package hr.fer.studentzkp.holder.util

import android.util.Base64
import org.json.JSONObject

/**
 * BBS+ Verifiable Credential (W3C VCDM 2.0 with studentzk-bbs-2023 cryptosuite)
 * parsing and extraction utilities.
 */

data class BbsVcData(
    val credentialJson: String,
    val issuer: String,
    val validFrom: String?,
    val validUntil: String?,
    val credentialSubject: JSONObject,
    val credentialStatus: JSONObject?,
    val proof: JSONObject,
    val messages: List<String>,
    val proofValueBytes: ByteArray,
)

object BbsVcUtils {

    /**
     * Parse a BBS VC JSON string into structured data.
     * Expects W3C VCDM 2.0 shape with `proof.cryptosuite = "studentzk-bbs-2023"`.
     */
    fun parse(jsonStr: String): BbsVcData {
        val obj = JSONObject(jsonStr)
        val proof = obj.getJSONObject("proof")

        val cryptosuite = proof.optString("cryptosuite")
        require(cryptosuite == "studentzk-bbs-2023") {
            "Wrong cryptosuite: expected \"studentzk-bbs-2023\", got \"$cryptosuite\""
        }

        val messagesArr = proof.getJSONArray("messages")
        val messages = (0 until messagesArr.length()).map { messagesArr.getString(it) }

        val proofValueB64 = proof.getString("proofValue")
        val proofBytes = Base64.decode(
            proofValueB64,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        return BbsVcData(
            credentialJson = jsonStr,
            issuer = obj.optString("issuer", ""),
            validFrom = obj.optString("validFrom").takeIf { it.isNotEmpty() },
            validUntil = obj.optString("validUntil").takeIf { it.isNotEmpty() },
            credentialSubject = obj.getJSONObject("credentialSubject"),
            credentialStatus = obj.optJSONObject("credentialStatus"),
            proof = proof,
            messages = messages,
            proofValueBytes = proofBytes,
        )
    }

    /** Extract well-known student fields from a parsed BBS VC. */
    fun extractStudentInfo(data: BbsVcData): StudentInfo {
        val cs = data.credentialSubject
        val isStudent = cs.optBoolean("is_student", false)
        val universityId = cs.optString("university_id").takeIf { it.isNotEmpty() }
        val ageOver18 = cs.optJSONObject("age_equal_or_over")?.optBoolean("18")

        val statusIdx = data.credentialStatus?.optInt("statusListIndex", -1) ?: -1
        val statusListUri = data.credentialStatus
            ?.optString("statusListCredential")
            ?.takeIf { it.isNotEmpty() }

        return StudentInfo(
            isStudent = isStudent,
            validUntil = data.validUntil,
            universityId = universityId,
            statusIdx = statusIdx,
            statusListUri = statusListUri,
            ageOver18 = ageOver18,
        )
    }

    /**
     * Extract the issuer's base URL from the proof's verificationMethod.
     * e.g. "http://localhost:8080/.well-known/studentzkp-bbs-key.json#kid" → "http://localhost:8080"
     */
    fun extractIssuerBaseUrl(data: BbsVcData): String? {
        val vm = data.proof.optString("verificationMethod")
        if (vm.isNullOrEmpty()) return null
        return try {
            val url = java.net.URL(vm)
            buildString {
                append(url.protocol)
                append("://")
                append(url.host)
                if (url.port > 0 && url.port != url.defaultPort) {
                    append(":${url.port}")
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract the key ID (`kid`) from the proof's verificationMethod fragment.
     * e.g. "http://…/studentzkp-bbs-key.json#abc123" → "abc123"
     */
    fun extractKid(data: BbsVcData): String? {
        val vm = data.proof.optString("verificationMethod")
        if (vm.isNullOrEmpty()) return null
        val fragment = vm.substringAfter('#', "")
        return fragment.takeIf { it.isNotEmpty() }
    }

    /**
     * Get disclosed attribute names from the canonical messages.
     * Messages are "credentialSubject.attr_name=value" — extract the attr part.
     */
    fun getAttributeNames(messages: List<String>): List<String> {
        return messages
            .filter { it.startsWith("credentialSubject.") }
            .map { it.substringBefore("=").removePrefix("credentialSubject.") }
    }

    /**
     * Get all message entries with their indices for selective disclosure UI.
     * Returns pairs of (index, human-readable label).
     */
    fun getIndexedAttributes(messages: List<String>): List<Pair<Int, String>> {
        return messages.mapIndexedNotNull { idx, msg ->
            if (msg.startsWith("credentialSubject.")) {
                idx to msg.substringBefore("=").removePrefix("credentialSubject.")
            } else {
                null
            }
        }
    }

    /**
     * Build a selective-disclosure presentation JSON.
     * Contains only the disclosed messages, their indices, and the derived BBS proof.
     */
    fun buildSelectivePresentation(
        data: BbsVcData,
        disclosedIndices: List<Int>,
        derivedProof: ByteArray,
    ): String {
        val disclosedMessages = disclosedIndices.map { data.messages[it] }
        val proofB64 = Base64.encodeToString(
            derivedProof,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        val presentation = JSONObject().apply {
            put("type", "BbsSelectiveDisclosure")
            put("issuer", data.issuer)
            put("validFrom", data.validFrom ?: JSONObject.NULL)
            put("validUntil", data.validUntil ?: JSONObject.NULL)
            put("credentialStatus", data.credentialStatus ?: JSONObject.NULL)
            put("proof", JSONObject().apply {
                put("type", "DataIntegrityProof")
                put("cryptosuite", "studentzk-bbs-2023")
                put("verificationMethod", data.proof.optString("verificationMethod"))
                put("derivedProofValue", proofB64)
                put("disclosedIndices", org.json.JSONArray(disclosedIndices))
                put("disclosedMessages", org.json.JSONArray(disclosedMessages))
                put("totalMessageCount", data.messages.size)
                put("nonce", "selective-disclosure")
            })
        }
        return presentation.toString()
    }

    /**
     * Check if a JSON string is a selective-disclosure presentation (vs full credential).
     */
    fun isSelectivePresentation(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            obj.optString("type") == "BbsSelectiveDisclosure"
        } catch (_: Exception) {
            false
        }
    }

    data class SelectivePresentationData(
        val issuer: String,
        val validFrom: String?,
        val validUntil: String?,
        val credentialStatus: JSONObject?,
        val verificationMethod: String,
        val derivedProofBytes: ByteArray,
        val disclosedIndices: List<Int>,
        val disclosedMessages: List<String>,
        val totalMessageCount: Int,
        val nonce: String,
    )

    /** Parse a selective-disclosure presentation JSON. */
    fun parseSelectivePresentation(jsonStr: String): SelectivePresentationData {
        val obj = JSONObject(jsonStr)
        require(obj.optString("type") == "BbsSelectiveDisclosure") {
            "Not a selective-disclosure presentation"
        }
        val proof = obj.getJSONObject("proof")
        val indicesArr = proof.getJSONArray("disclosedIndices")
        val messagesArr = proof.getJSONArray("disclosedMessages")

        return SelectivePresentationData(
            issuer = obj.optString("issuer", ""),
            validFrom = obj.optString("validFrom").takeIf { it.isNotEmpty() && it != "null" },
            validUntil = obj.optString("validUntil").takeIf { it.isNotEmpty() && it != "null" },
            credentialStatus = obj.optJSONObject("credentialStatus"),
            verificationMethod = proof.getString("verificationMethod"),
            derivedProofBytes = Base64.decode(
                proof.getString("derivedProofValue"),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            ),
            disclosedIndices = (0 until indicesArr.length()).map { indicesArr.getInt(it) },
            disclosedMessages = (0 until messagesArr.length()).map { messagesArr.getString(it) },
            totalMessageCount = proof.getInt("totalMessageCount"),
            nonce = proof.optString("nonce", "selective-disclosure"),
        )
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
