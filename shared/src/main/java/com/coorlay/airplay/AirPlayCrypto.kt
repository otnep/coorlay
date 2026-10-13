package com.coorlay.airplay

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** BouncyCastle-backed primitives for the CarPlay pairing and control channel. */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private const val LABEL_SIZE = 8
    private val random = SecureRandom()

    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    /**
     * ChaCha20-Poly1305 opener reused for every packet of one stream (one thread). [chachaOpen] made a new
     * cipher, key copy and four arrays per audio packet; on Dalvik that fed GC_FOR_ALLOC pauses of 36–42 ms
     * during CarPlay (2026-10-12). Here only the caller's output array is new.
     */
    class Opener(key: ByteArray) {
        private val cipher = ChaCha20Poly1305()
        private val keyParameter = KeyParameter(key.copyOf())
        private val nonce = ByteArray(NONCE_SIZE)

        /**
         * Opens [sealedLength] bytes (ciphertext then 16-byte tag) at [sealedOffset] of [input] into [output] at
         * [outputOffset], with [aadLength] bytes of associated data at [aadOffset] and the 8 nonce bytes at
         * [nonceOffset] placed after four zero bytes. Returns the plaintext length; a bad tag throws.
         */
        fun open(input: ByteArray, aadOffset: Int, aadLength: Int, sealedOffset: Int, sealedLength: Int,
            nonceOffset: Int, output: ByteArray, outputOffset: Int): Int {
            input.copyInto(nonce, NONCE_SIZE - LABEL_SIZE, nonceOffset, nonceOffset + LABEL_SIZE)
            cipher.init(false, AEADParameters(keyParameter, MAC_BITS, nonce))
            cipher.processAADBytes(input, aadOffset, aadLength)
            val processed = cipher.processBytes(input, sealedOffset, sealedLength, output, outputOffset)
            return processed + cipher.doFinal(output, outputOffset + processed)
        }

        companion object {
            const val TAG_SIZE = MAC_BITS / 8
        }
    }

    /** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        return nonce
    }

    /** 12-byte nonce from an eight-byte ASCII label placed after four zero bytes. */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.asciiBytes()
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))
        return nonce
    }

    private fun digest(digest: Digest, parts: Array<out ByteArray>): ByteArray {
        for (part in parts) digest.update(part, 0, part.size)
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }
}
