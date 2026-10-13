package com.coorlay.coolray

import com.coorlay.airplay.AirPlayCrypto
import com.coorlay.airplay.ScreenCodec
import com.coorlay.airplay.ScreenFrameDecryptor
import org.conscrypt.Conscrypt
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Native video AEAD from our pinned Conscrypt 2.5.2, without API-19 Cipher.updateAAD. */
internal object LegacyVideoCrypto {
    private class NativeApi {
        private val type = Class.forName("org.conscrypt.NativeCrypto")
        val algorithm: Long = (type.getDeclaredMethod("EVP_aead_chacha20_poly1305")
            .apply { isAccessible = true }.invoke(null) as Number).toLong().also { check(it != 0L) }
        val open: Method = type.getDeclaredMethod("EVP_AEAD_CTX_open",
            java.lang.Long.TYPE, ByteArray::class.java, Integer.TYPE, ByteArray::class.java,
            Integer.TYPE, ByteArray::class.java, ByteArray::class.java, Integer.TYPE,
            Integer.TYPE, ByteArray::class.java).apply { isAccessible = true }

        fun decrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, sealed: ByteArray, sealedLength: Int = sealed.size): ByteArray {
            val output = ByteArray(sealedLength - ScreenCodec.TAG_SIZE)
            decryptInto(key, nonce, header, sealed, sealedLength, output)
            return output
        }

        fun decryptInto(key: ByteArray, nonce: ByteArray, header: ByteArray, sealed: ByteArray, sealedLength: Int,
            output: ByteArray): Int {
            require(key.size == 32 && nonce.size == 12 && sealedLength >= ScreenCodec.TAG_SIZE && sealedLength <= sealed.size)
            val plain = sealedLength - ScreenCodec.TAG_SIZE
            require(output.size >= plain)
            val length = try {
                (open.invoke(null, algorithm, key, ScreenCodec.TAG_SIZE, output, 0,
                    nonce, sealed, 0, sealedLength, header) as Number).toInt()
            } catch (error: InvocationTargetException) { throw error.targetException }
            check(length == plain) { "Unexpected native video plaintext length" }
            return length
        }
    }

    private val native by lazy {
        Conscrypt.checkAvailability()
        val api = NativeApi()
        // Verify the pinned JNI contract with a synthetic key before touching a stream.
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val nonce = AirPlayCrypto.nonce64(42)
        val aad = ByteArray(128) { it.toByte() }
        val plain = byteArrayOf(0, 0, 0, 2, 0x65, 0x12)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        check(api.decrypt(key, nonce, aad, sealed).contentEquals(plain)) { "Native video AEAD self-test failed" }
        val corrupt = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        var rejected = false
        try { api.decrypt(key, nonce, aad, corrupt) } catch (_: javax.crypto.BadPaddingException) { rejected = true }
        check(rejected) { "Native video AEAD accepted an invalid tag" }
        api
    }

    fun create(key: ByteArray, report: (String) -> Unit = {}): ScreenFrameDecryptor {
        require(key.size == 32)
        return try {
            val api = native
            val streamKey = key.copyOf()
            report("Video crypto backend=Conscrypt-2.5.2 native ChaCha20-Poly1305 selfTest=passed")
            object : ScreenFrameDecryptor {
                // An authentication failure propagates; never retry it with another backend.
                override fun open(counter: Long, header: ByteArray, body: ByteArray, length: Int) =
                    api.decrypt(streamKey, AirPlayCrypto.nonce64(counter), header, body, length)
                override fun openInto(counter: Long, header: ByteArray, body: ByteArray, length: Int, output: ByteArray) =
                    api.decryptInto(streamKey, AirPlayCrypto.nonce64(counter), header, body, length, output)
            }
        } catch (error: Exception) {
            fallback(key, error, report)
        } catch (error: LinkageError) {
            fallback(key, error, report)
        }
    }

    private fun fallback(key: ByteArray, error: Throwable, report: (String) -> Unit): ScreenFrameDecryptor {
        report("Video crypto backend=BouncyCastle fallback=${error.javaClass.name}: ${error.message}")
        val streamKey = key.copyOf()
        return ScreenFrameDecryptor { counter, header, body, length ->
            ScreenCodec.decryptFrame(streamKey, counter, header, if (length == body.size) body else body.copyOf(length))
        }
    }
}
