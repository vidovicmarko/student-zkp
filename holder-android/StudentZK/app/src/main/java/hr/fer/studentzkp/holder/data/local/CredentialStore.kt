package hr.fer.studentzkp.holder.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import hr.fer.studentzkp.holder.data.model.StoredCredential
import org.json.JSONArray
import org.json.JSONObject

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "student_zkp_wallet",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val settingsPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("student_zkp_settings", Context.MODE_PRIVATE)
    }

    fun loadAll(): List<StoredCredential> {
        val json = prefs.getString(KEY_CREDENTIALS, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
    }

    fun save(credential: StoredCredential) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == credential.id }
        list.add(0, credential)
        persist(list)
    }

    fun delete(credentialId: String) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == credentialId }
        persist(list)
    }

    fun getServerUrl(): String =
        settingsPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun saveServerUrl(url: String) {
        settingsPrefs.edit().putString(KEY_SERVER_URL, url.trimEnd('/')).apply()
    }

    private fun persist(list: List<StoredCredential>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_CREDENTIALS, arr.toString()).apply()
    }

    private fun toJson(c: StoredCredential): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("sdJwt", c.sdJwt)
        put("studentId", c.studentId)
        put("issuedAt", c.issuedAt)
        put("validUntil", c.validUntil ?: "")
        put("credentialType", c.credentialType)
        put("universityId", c.universityId ?: "")
        put("isStudent", c.isStudent)
        put("statusIdx", c.statusIdx)
        put("statusListUri", c.statusListUri ?: "")
    }

    private fun fromJson(o: JSONObject): StoredCredential = StoredCredential(
        id = o.getString("id"),
        sdJwt = o.getString("sdJwt"),
        studentId = o.getString("studentId"),
        issuedAt = o.getLong("issuedAt"),
        validUntil = o.optString("validUntil").takeIf { it.isNotEmpty() },
        credentialType = o.optString("credentialType", "UniversityStudent"),
        universityId = o.optString("universityId").takeIf { it.isNotEmpty() },
        isStudent = o.optBoolean("isStudent", true),
        statusIdx = o.optInt("statusIdx", -1),
        statusListUri = o.optString("statusListUri").takeIf { it.isNotEmpty() },
    )

    companion object {
        private const val KEY_CREDENTIALS = "credentials"
        private const val KEY_SERVER_URL = "server_url"
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
    }
}
