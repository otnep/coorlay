package com.coorlay.coolray

import android.os.Build
import androidx.multidex.MultiDexApplication
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.Provider
import java.security.SecureRandom
import java.security.SecureRandomSpi
import java.security.Security

class CoolrayApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        LegacyPrng.install()
    }
}

/** Android 4.1–4.3 OpenSSL PRNG workaround, before any pairing key is generated.
 * See https://android-developers.googleblog.com/2013/08/some-securerandom-thoughts.html
 */
internal object LegacyPrng {
    @Synchronized fun install() {
        if (Build.VERSION.SDK_INT > 18) return
        val entropy = ByteArray(1024)
        DataInputStream(FileInputStream("/dev/urandom")).use { it.readFully(entropy) }
        try {
            if (Build.VERSION.SDK_INT >= 16) {
                val crypto = Class.forName("org.apache.harmony.xnet.provider.jsse.NativeCrypto")
                crypto.getMethod("RAND_seed", ByteArray::class.java).invoke(null, entropy)
            }
            val provider = object : Provider("CoolrayKernelRandom", 1.0, "Linux kernel random source") {
                init { put("SecureRandom.SHA1PRNG", KernelRandomSpi::class.java.name) }
            }
            if (Security.getProvider(provider.name) == null) Security.insertProviderAt(provider, 1)
            check(SecureRandom().provider.name == provider.name)
            check(SecureRandom.getInstance("SHA1PRNG").provider.name == provider.name)
        } catch (error: Exception) {
            throw SecurityException("Could not initialize Android 4.3 cryptographic random source", error)
        } finally { entropy.fill(0) }
    }
}

/** Public no-argument constructor is required by java.security.Provider. */
class KernelRandomSpi : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray) {
        // Mixing extra seed is optional; output always comes from the kernel entropy pool.
        runCatching { FileOutputStream("/dev/urandom").use { it.write(seed) } }
    }
    override fun engineNextBytes(bytes: ByteArray) {
        DataInputStream(FileInputStream("/dev/urandom")).use { it.readFully(bytes) }
    }
    override fun engineGenerateSeed(size: Int): ByteArray = ByteArray(size).also(::engineNextBytes)
}
