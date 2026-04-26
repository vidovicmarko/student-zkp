package hr.fer.studentzkp.holder.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Manages the holder's hardware-bound ES256 key in AndroidKeyStore.
 * Used for generating Key-Binding JWTs in the OID4VCI proof flow.
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

    /** Returns the public key as a JWK JSONObject for embedding in proof JWTs. */
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

    /**
     * Build an OID4VCI proof JWT.
     * Header: {"alg":"ES256","typ":"openid4vci-proof+jwt","jwk":<pub>}
     * Payload: {"aud":<issuerUrl>,"iat":<now>,"nonce":<cNonce>}
     */
    fun buildProofJwt(issuerUrl: String, cNonce: String): String {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        val privateKey = ks.getKey(KEY_ALIAS, null) as java.security.PrivateKey

        val headerJson = JSONObject()
            .put("alg", "ES256")
            .put("typ", "openid4vci-proof+jwt")
            .put("jwk", publicKeyJwk())
        val payloadJson = JSONObject()
            .put("aud", issuerUrl)
            .put("iat", System.currentTimeMillis() / 1000)
            .put("nonce", cNonce)

        val headerB64 = b64url(headerJson.toString().toByteArray(Charsets.UTF_8))
        val payloadB64 = b64url(payloadJson.toString().toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64"

        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }
        // Convert DER-encoded ECDSA sig to raw R||S (64 bytes)
        val rawSig = derToRaw(sig)
        val sigB64 = b64url(rawSig)
        return "$signingInput.$sigB64"
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

    private fun b64url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Convert DER ECDSA signature → fixed 64-byte R||S */
    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 2 // skip SEQUENCE tag + length
        val rLen = der[offset + 1].toInt() and 0xFF
        val r = der.copyOfRange(offset + 2, offset + 2 + rLen)
        offset += 2 + rLen
        val sLen = der[offset + 1].toInt() and 0xFF
        val s = der.copyOfRange(offset + 2, offset + 2 + sLen)
        val result = ByteArray(64)
        val rTrimmed = stripLeadingZero(r)
        val sTrimmed = stripLeadingZero(s)
        rTrimmed.copyInto(result, 32 - rTrimmed.size)
        sTrimmed.copyInto(result, 64 - sTrimmed.size)
        return result
    }
}
