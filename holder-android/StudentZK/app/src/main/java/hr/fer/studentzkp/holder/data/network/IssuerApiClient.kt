package hr.fer.studentzkp.holder.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class IssuerApiClient(private val baseUrl: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val json = "application/json".toMediaType()
    private val formEncoded = "application/x-www-form-urlencoded".toMediaType()

    /** POST /dev/credential/{studentId} — issues credential bypassing OID4VCI */
    suspend fun devIssueCredential(
        studentId: String,
        cnfJwk: JSONObject? = null,
    ): Result<DevCredentialResponse> = runCatching {
        val body = if (cnfJwk != null) {
            JSONObject().put("cnfJwk", cnfJwk).toString().toRequestBody(json)
        } else {
            "{}".toRequestBody(json)
        }
        val request = Request.Builder()
            .url("$baseUrl/dev/credential/$studentId")
            .post(body)
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server ${resp.code}: ${resp.message}")
                val obj = JSONObject(resp.body!!.string())
                DevCredentialResponse(
                    credentialId = obj.getString("credentialId"),
                    statusIdx = obj.getInt("statusIdx"),
                    sdJwt = obj.getString("sdJwt"),
                )
            }
        }
    }

    /** POST /dev/credential-offer/{studentId} */
    suspend fun createCredentialOffer(studentId: String): Result<CredentialOfferResponse> =
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/dev/credential-offer/$studentId")
                .post("{}".toRequestBody(json))
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Server ${resp.code}: ${resp.message}")
                    val obj = JSONObject(resp.body!!.string())
                    CredentialOfferResponse(
                        offerId = obj.getString("offer_id"),
                        preAuthorizedCode = obj.getString("pre_authorized_code"),
                        credentialOfferUri = obj.getString("credential_offer_uri"),
                        deepLink = obj.getString("deep_link"),
                        expiresInSeconds = obj.getInt("expires_in_seconds"),
                    )
                }
            }
        }

    /** POST /token */
    suspend fun exchangeToken(preAuthorizedCode: String): Result<TokenResponse> = runCatching {
        val formBody =
            "grant_type=${encode("urn:ietf:params:oauth:grant-type:pre-authorized_code")}" +
                "&pre-authorized_code=${encode(preAuthorizedCode)}"
        val request = Request.Builder()
            .url("$baseUrl/token")
            .post(formBody.toRequestBody(formEncoded))
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server ${resp.code}: ${resp.message}")
                val obj = JSONObject(resp.body!!.string())
                TokenResponse(
                    accessToken = obj.getString("access_token"),
                    tokenType = obj.getString("token_type"),
                    expiresIn = obj.getInt("expires_in"),
                    cNonce = obj.getString("c_nonce"),
                    cNonceExpiresIn = obj.getInt("c_nonce_expires_in"),
                )
            }
        }
    }

    /** GET /statuslist/uni-2026.json */
    suspend fun getStatusList(): Result<StatusListResponse> = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/statuslist/uni-2026.json")
            .get()
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server ${resp.code}: ${resp.message}")
                val root = JSONObject(resp.body!!.string())
                val sl = root.getJSONObject("status_list")
                StatusListResponse(bits = sl.getInt("bits"), lst = sl.getString("lst"))
            }
        }
    }

    /** GET /health */
    suspend fun checkHealth(): Result<Boolean> = runCatching {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp -> resp.isSuccessful }
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
