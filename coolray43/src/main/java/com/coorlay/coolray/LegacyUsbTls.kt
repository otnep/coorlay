package com.coorlay.coolray

import com.coorlay.transport.LockdownTlsEngineFactory
import org.conscrypt.Conscrypt
import java.security.Provider

/** API-18's platform SSLEngine cannot speak the TLS required by current iPhone Lockdown. */
object LegacyUsbTls {
    private var provider: Provider? = null

    @Synchronized
    fun install(): String {
        val selected = provider ?: run {
            Conscrypt.checkAvailability()
            Conscrypt.newProviderBuilder().setName("coorlayTLS").provideTrustManager(false).build().also {
                LockdownTlsEngineFactory.configureUsbProvider(it)
                provider = it
            }
        }
        val version = Conscrypt.version()
        return "USB TLS bundled=${selected.name} Conscrypt=${version.major()}.${version.minor()}.${version.patch()} " +
            "scope=USB-Lockdown native=ready"
    }
}
