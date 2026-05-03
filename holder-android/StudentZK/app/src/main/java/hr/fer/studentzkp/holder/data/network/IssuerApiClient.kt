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
        .addInterceptor { chain ->
            // Bypass the ngrok browser-warning interception page when tunnelling via ngrok
            val req = chain.request().newBuilder()
                .header("ngrok-skip-browser-warning", "true")
                .build()
            chain.proceed(req)
        }
        .build()

    private val json = "application/json".toMediaType()

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
                val bbsVc = obj.optJSONObject("bbsVc")
                    ?: throw IOException("Server response missing bbsVc field")
                DevCredentialResponse(
                    credentialId = obj.getString("credentialId"),
                    statusIdx = obj.getInt("statusIdx"),
                    bbsVcJson = bbsVc.toString(),
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

    /** GET /.well-known/studentzkp-bbs-key.json — fetch issuer's BBS+ public key */
    suspend fun getBbsPublicKey(issuerBaseUrl: String? = null): Result<BbsPublicKeyResponse> = runCatching {
        val base = issuerBaseUrl ?: baseUrl
        val request = Request.Builder()
            .url("$base/.well-known/studentzkp-bbs-key.json")
            .get()
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server ${resp.code}: ${resp.message}")
                val obj = JSONObject(resp.body!!.string())
                BbsPublicKeyResponse(
                    kid = obj.getString("kid"),
                    publicKey = obj.getString("publicKey"),
                )
            }
        }
    }
}
