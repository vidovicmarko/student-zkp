package hr.fer.studentzkp.holder.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Manages the holder's hardware-bound ES256 key in AndroidKeyStore.
 * Used for embedding a cnf JWK in issued credentials.
 */
object HolderKeyManager {

    private const val KEY_ALIAS = "studentzk.holder.cnf"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** Create the keypair if it doesn't exist yet. Falls back to TEE if StrongBox unavailable. */
    fun ensureKeyExists(context: Context) {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return

        // Try StrongBox first, fall back to software-backed TEE
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER).run {
                initialize(buildSpec(strongBox = true))
                generateKeyPair()
            }
        } catch (_: Exception) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER).run {
                initialize(buildSpec(strongBox = false))
                generateKeyPair()
            }
        }
    }

    /** Returns the public key as a JWK JSONObject for embedding in credentials. */
    fun publicKeyJwk(): JSONObject {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val pub = ks.getCertificate(KEY_ALIAS).publicKey as ECPublicKey
        val w = pub.w
        val x = w.affineX.toByteArray().let { stripLeadingZero(it) }
        val y = w.affineY.toByteArray().let { stripLeadingZero(it) }
        return JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", Base64.encodeToString(x, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .put("y", Base64.encodeToString(y, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    }

    fun hasKey(): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        return ks.containsAlias(KEY_ALIAS)
    }

    private fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        return builder.build()
    }

    private fun stripLeadingZero(bytes: ByteArray): ByteArray =
        if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size)
        else bytes
}
