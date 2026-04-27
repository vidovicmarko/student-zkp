package hr.fer.studentzkp.holder.crypto

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

// Mirror of issuer-backend's BbsCryptoBridge.kt. Same C ABI (six exported
// `studentzkp_*` symbols, see crypto-core/include/studentzkp_crypto.h), same
// Kotlin facade — only the package differs.
//
// On Android the .so lives at jniLibs/<abi>/libstudentzkp_crypto.so (built
// by `scripts/build-crypto.sh android`). JNA's loader picks it up via
// System.mapLibraryName + Native.load.
//
// ⚠ 64-bit ABIs only (`size_t = uint64_t`). On 32-bit ABIs (armv7, x86) the
// `long` mapping for size_t is wrong; cargo-ndk still produces the .so so the
// app loads, but consumers must keep BbsCrypto away from those code paths.

private const val STATUS_OK = 0
private const val STATUS_ERR_NULL_POINTER = 1
private const val STATUS_ERR_INVALID_INPUT = 2
private const val STATUS_ERR_CRYPTO = 3
private const val STATUS_ERR_INTERNAL = 4

@Structure.FieldOrder("ptr", "len", "cap")
open class ByteBuf : Structure() {
    @JvmField var ptr: Pointer? = null
    @JvmField var len: Long = 0
    @JvmField var cap: Long = 0
}

@Structure.FieldOrder("ptr", "len")
open class ByteSlice : Structure() {
    @JvmField var ptr: Pointer? = null
    @JvmField var len: Long = 0

    class ByValue : ByteSlice(), Structure.ByValue
}

interface StudentZkpCryptoLib : Library {

    fun studentzkp_bbs_keygen(outPk: ByteBuf, outSk: ByteBuf): Int

    fun studentzkp_bbs_sign(
        messages: Pointer?,
        messagesCount: Long,
        secretKey: ByteSlice.ByValue,
        outSignature: ByteBuf,
    ): Int

    fun studentzkp_bbs_derive_proof(
        signature: ByteSlice.ByValue,
        messages: Pointer?,
        messagesCount: Long,
        disclosedIndices: Pointer?,
        disclosedCount: Long,
        nonce: ByteSlice.ByValue,
        outProof: ByteBuf,
    ): Int

    fun studentzkp_bbs_verify_proof(
        proof: ByteSlice.ByValue,
        publicKey: ByteSlice.ByValue,
        disclosedIndices: Pointer?,
        disclosedCount: Long,
        disclosedMessages: Pointer?,
        disclosedMessagesCount: Long,
        totalMessageCount: Long,
        nonce: ByteSlice.ByValue,
        outValid: Pointer,
    ): Int

    fun studentzkp_buf_free(buf: ByteBuf)

    fun studentzkp_last_error(outErr: ByteBuf): Int

    companion object {
        private const val LIB_NAME = "studentzkp_crypto"

        val INSTANCE: StudentZkpCryptoLib by lazy {
            Native.load(LIB_NAME, StudentZkpCryptoLib::class.java)
        }
    }
}

class BbsCryptoException(msg: String, val status: Int) : RuntimeException(msg)

object BbsCrypto {

    data class Keypair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun keygen(): Keypair {
        val outPk = ByteBuf()
        val outSk = ByteBuf()
        val rc = StudentZkpCryptoLib.INSTANCE.studentzkp_bbs_keygen(outPk, outSk)
        try {
            check(rc, "bbs_keygen")
            return Keypair(readBuf(outPk), readBuf(outSk))
        } finally {
            free(outPk); free(outSk)
        }
    }

    fun sign(messages: List<ByteArray>, secretKey: ByteArray): ByteArray {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val out = ByteBuf()
        val pin = pinByteArrays(messages)
        val sk = pin.nonOwnedSlice(secretKey)
        try {
            val rc = StudentZkpCryptoLib.INSTANCE.studentzkp_bbs_sign(
                pin.slicesPtr, pin.count, sk, out,
            )
            check(rc, "bbs_sign")
            return readBuf(out)
        } finally {
            free(out); pin.close()
        }
    }

    fun deriveProof(
        signature: ByteArray,
        messages: List<ByteArray>,
        disclosedIndices: List<Int>,
        nonce: ByteArray,
    ): ByteArray {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val out = ByteBuf()
        val pin = pinByteArrays(messages)
        val idxPin = pinIndices(disclosedIndices)
        val sigSlice = pin.nonOwnedSlice(signature)
        val nonceSlice = pin.nonOwnedSlice(nonce)
        try {
            val rc = StudentZkpCryptoLib.INSTANCE.studentzkp_bbs_derive_proof(
                sigSlice,
                pin.slicesPtr, pin.count,
                idxPin.first, idxPin.second,
                nonceSlice,
                out,
            )
            check(rc, "bbs_derive_proof")
            return readBuf(out)
        } finally {
            free(out); pin.close()
        }
    }

    fun verifyProof(
        proof: ByteArray,
        publicKey: ByteArray,
        disclosedIndices: List<Int>,
        disclosedMessages: List<ByteArray>,
        totalMessageCount: Int,
        nonce: ByteArray,
    ): Boolean {
        require(disclosedIndices.size == disclosedMessages.size) {
            "disclosedIndices/messages length mismatch: ${disclosedIndices.size} vs ${disclosedMessages.size}"
        }
        require(totalMessageCount > 0) { "totalMessageCount must be > 0" }
        val pin = pinByteArrays(disclosedMessages)
        val idxPin = pinIndices(disclosedIndices)
        val proofSlice = pin.nonOwnedSlice(proof)
        val pkSlice = pin.nonOwnedSlice(publicKey)
        val nonceSlice = pin.nonOwnedSlice(nonce)
        val outValid = Memory(1)
        try {
            val rc = StudentZkpCryptoLib.INSTANCE.studentzkp_bbs_verify_proof(
                proofSlice,
                pkSlice,
                idxPin.first, idxPin.second,
                pin.slicesPtr, pin.count,
                totalMessageCount.toLong(),
                nonceSlice,
                outValid,
            )
            check(rc, "bbs_verify_proof")
            return outValid.getByte(0).toInt() == 1
        } finally {
            outValid.close(); pin.close()
        }
    }

    private fun check(status: Int, op: String) {
        if (status == STATUS_OK) return
        val message = lastError() ?: "unknown error"
        val statusName = when (status) {
            STATUS_ERR_NULL_POINTER -> "NULL_POINTER"
            STATUS_ERR_INVALID_INPUT -> "INVALID_INPUT"
            STATUS_ERR_CRYPTO -> "CRYPTO"
            STATUS_ERR_INTERNAL -> "INTERNAL"
            else -> "STATUS_$status"
        }
        throw BbsCryptoException("$op failed: $statusName — $message", status)
    }

    private fun lastError(): String? {
        val buf = ByteBuf()
        val rc = StudentZkpCryptoLib.INSTANCE.studentzkp_last_error(buf)
        if (rc != STATUS_OK) return null
        return try {
            if (buf.len == 0L) null else readBuf(buf).toString(Charsets.UTF_8)
        } finally {
            free(buf)
        }
    }

    private fun readBuf(buf: ByteBuf): ByteArray {
        val ptr = buf.ptr ?: return ByteArray(0)
        if (buf.len == 0L) return ByteArray(0)
        return ptr.getByteArray(0, buf.len.toInt())
    }

    private fun free(buf: ByteBuf) {
        if (buf.ptr != null && buf.cap > 0L) {
            StudentZkpCryptoLib.INSTANCE.studentzkp_buf_free(buf)
        }
    }

    private fun pinByteArrays(arrays: List<ByteArray>): Pin {
        val backing = arrays.map { bytes ->
            if (bytes.isEmpty()) null
            else Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
        }
        val slicesArr = ByteSlice().toArray(arrays.size).map { it as ByteSlice }
        val slicesPtr: Pointer? = if (slicesArr.isEmpty()) null else {
            for (i in arrays.indices) {
                slicesArr[i].ptr = backing[i]
                slicesArr[i].len = arrays[i].size.toLong()
                slicesArr[i].write()
            }
            slicesArr[0].pointer
        }
        return Pin(slicesPtr, arrays.size.toLong(), backing)
    }

    private fun pinIndices(indices: List<Int>): Pair<Pointer?, Long> {
        if (indices.isEmpty()) return null to 0L
        val mem = Memory(indices.size * 8L)
        for ((i, idx) in indices.withIndex()) {
            require(idx >= 0) { "disclosed index must be non-negative: $idx" }
            mem.setLong(i * 8L, idx.toLong())
        }
        return mem to indices.size.toLong()
    }

    private class Pin(
        val slicesPtr: Pointer?,
        val count: Long,
        private val backing: List<Memory?>,
        private val extras: MutableList<Memory> = mutableListOf(),
    ) : AutoCloseable {

        fun nonOwnedSlice(bytes: ByteArray): ByteSlice.ByValue {
            val slice = ByteSlice.ByValue()
            if (bytes.isEmpty()) {
                slice.ptr = null
                slice.len = 0
            } else {
                val mem = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
                extras += mem
                slice.ptr = mem
                slice.len = bytes.size.toLong()
            }
            return slice
        }

        override fun close() {
            backing.forEach { it?.close() }
            extras.forEach { it.close() }
        }
    }
}
