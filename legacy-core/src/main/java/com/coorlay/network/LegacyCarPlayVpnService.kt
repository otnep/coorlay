package com.coorlay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.coorlay.airplay.AirPlayConfig
import com.coorlay.airplay.AirPlayIdentity
import com.coorlay.airplay.AirPlayMediaHandler
import com.coorlay.airplay.AirPlaySession
import com.coorlay.airplay.AirPlaySessionListener
import com.coorlay.airplay.PairingStore
import com.coorlay.mfi.MfiAuthenticator
import com.coorlay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val diagnostic: (String) -> Unit = {},
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        onDiagnostic: (String) -> Unit = {},
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd
            onDiagnostic("VPN established address=$linkLocal/$LINK_PREFIX mtu=$TUN_MTU fd=${tunFd.fd}")
            val blocking = com.coorlay.transport.LegacyUsbNative.setBlocking(tunFd.fd)
            if (blocking < 0) throw IOException("Could not configure blocking VPN I/O: errno=${-blocking}")

            onDiagnostic("NCM own-address neighbor responder ready address=$linkLocal")
            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac, onDiagnostic, address.address) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media, onDiagnostic),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        diagnostic: (String) -> Unit = {},
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media, diagnostic),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** The port the AirPlay listener actually bound; the wireless path may fall back from 7000. */
    @Synchronized
    fun listeningPort(): Int? = serverSocket?.localPort

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val bindPort = { port: Int ->
            val server = ServerSocket()
            try {
                // A listener from the previous run may still be in TIME_WAIT on the same address.
                server.reuseAddress = true
                val address = replacement.address
                val scoped = if (address is Inet6Address && address.isLinkLocalAddress) {
                    val iface = NetworkInterface.getByInetAddress(address)
                    if (iface != null) Inet6Address.getByAddress(null, address.address, iface) else address
                } else address
                server.bind(InetSocketAddress(scoped, port))
                server
            } catch (error: Exception) {
                runCatching { server.close() }
                throw error
            }
        }
        val server = if (tun != null) {
            retryUsbListenerBind({ bindPort(replacement.config.port) }, report = replacement.diagnostic)
        } else {
            WirelessListenerPort.bind(replacement.config.port, replacement.diagnostic, bindPort)
        }
        replacement.diagnostic("AirPlay listening address=${server.inetAddress.hostAddress} port=${server.localPort}")
        if (tun != null) runCatching {
            val iface = NetworkInterface.getByInetAddress(replacement.address)
            val addresses = iface?.inetAddresses?.toList()?.filterIsInstance<Inet6Address>()
                ?.joinToString(",") { it.hostAddress.orEmpty() }.orEmpty()
            replacement.diagnostic("VPN interface=${iface?.name ?: "unknown"} IPv6 addresses=$addresses")
        }.onFailure { replacement.diagnostic("VPN interface diagnostics unavailable: ${it.javaClass.simpleName}") }
        attachment = replacement
        serverSocket = server
        Thread(
            { acceptLoop(generation, server) },
            "airplay-accept",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                attachment?.diagnostic?.invoke("AirPlay connection accepted from ${socket.remoteSocketAddress}")
                attachment?.listener?.let { listener -> runCatching { listener.onConnectionAccepted() } }
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "coorlay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "coorlay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
