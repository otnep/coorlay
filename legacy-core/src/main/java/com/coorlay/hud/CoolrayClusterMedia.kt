package com.coorlay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import com.coorlay.media.CarPlayNowPlaying
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CarPlay's now-playing track on the Coolray instrument cluster, as the head unit's own players show theirs.
 *
 * The OEM players report media to extraservice's relay "com.ecarx.xui.adaptapi.diminteraction.MediaService"
 * (IDimTransListener). It forwards every call to the listeners registered on its binder, and the cluster
 * server IPKServer registers there (MediaBean). Android hands every client that binds with an equal Intent
 * the same binder, so coorlay binds exactly as IPKServer does. The cluster shows only music sources; the
 * source "Online" (code 6) is the one meant for streaming apps (firmware 10500: IPKServer MediaInfoPackage).
 */
class CoolrayClusterMedia(
    context: Context,
    /** True while another head-unit source (MODE: radio, USB, Bluetooth) owns the speakers. */
    private val yielded: () -> Boolean = { false },
    private val report: (String) -> Unit = {},
) : Closeable {
    private val app = context.applicationContext
    private val calls: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "coolray-cluster-media").apply { isDaemon = true }
    }
    @Volatile private var service: IBinder? = null
    private var bound = false
    private var closed = false

    // Guarded by this.
    private var current = CarPlayNowPlaying()
    private var currentAt = 0L
    private var claimed = false
    private var sentTrack: Track? = null
    private var sentPlaying: Boolean? = null
    private var trackNumber = 0
    private var failures = 0

    private data class Track(val title: String, val album: String, val artist: String)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            report("CLUSTER media: connected to ${name?.className}")
            submit { synchronized(this@CoolrayClusterMedia) { publish(force = true) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            report("CLUSTER media: service disconnected")
        }
    }

    init {
        // The same Intent as IPKServer's MediaBean: action only, so both share one service binder.
        bound = runCatching { app.bindService(Intent(ACTION), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { report("CLUSTER media: bind failed ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        if (!bound) report("CLUSTER media: no cluster media service on this head unit")
    }

    /** The iPhone's now-playing state changed. */
    fun update(nowPlaying: CarPlayNowPlaying) = submit {
        synchronized(this) {
            current = nowPlaying
            currentAt = System.nanoTime()
            publish(force = false)
        }
    }

    /** Called about once a second: the cluster's progress bar and a return from another source. */
    fun tick() = submit {
        synchronized(this) {
            publish(force = false)
            if (claimed && current.playing) progress()
        }
    }

    private fun publish(force: Boolean) {
        if (closed) return
        val title = current.title
        if (yielded()) {
            // The OEM source announced itself to the cluster; reclaim only when CarPlay music plays again.
            if (claimed) report("CLUSTER media: another source owns the speakers")
            claimed = false
            sentTrack = null
            sentPlaying = null
            return
        }
        if (!claimed) {
            if (!current.playing || title == null) return
            if (!call(TX_SOURCE) { writeInt(SOURCE_ONLINE) }) return
            claimed = true
            sentTrack = null
            sentPlaying = null
            report("CLUSTER media: source Online")
        }
        if (title != null) {
            val track = Track(title, current.album.orEmpty(), current.artist.orEmpty())
            if (force || track != sentTrack) {
                trackNumber++
                // For "Online" the cluster splits "mediaUniqueId&&&coverPath"; a path with "http" means no cover.
                val sent = call(TX_MEDIA_INFO) {
                    writeInt(0)
                    writeString(track.title)
                    writeString(track.album.ifEmpty { " " })
                    writeString(track.artist.ifEmpty { " " })
                    writeString("carplay$trackNumber&&&http:none")
                    writeInt(SOURCE_ONLINE)
                }
                if (sent) {
                    sentTrack = track
                    sentPlaying = null
                    report("CLUSTER media: track title=\"${track.title}\" artist=\"${track.artist}\" album=\"${track.album}\"")
                }
            }
        }
        if (force || current.playing != sentPlaying) {
            if (call(TX_PLAY_STATUS) { writeInt(if (current.playing) STATUS_PLAY else STATUS_PAUSE); writeInt(SOURCE_ONLINE) }) {
                sentPlaying = current.playing
            }
        }
    }

    private fun progress() {
        val duration = current.durationMillis ?: return
        val elapsed = current.elapsedMillis ?: return
        val now = (elapsed + (System.nanoTime() - currentAt) / 1_000_000L).coerceAtMost(duration)
        call(TX_PROGRESS) {
            writeInt(now.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            writeInt(duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            writeString(null)
        }
    }

    /** One IDimTransListener call; false when the service is not bound or the call failed. */
    private fun call(code: Int, args: Parcel.() -> Unit): Boolean {
        val binder = service ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.args()
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (error: Exception) {
            if (failures++ < MAX_FAILURE_REPORTS) {
                report("CLUSTER media: call $code failed ${error.javaClass.simpleName}: ${error.message}")
            }
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Runs [task] on the call thread; after [close] it is dropped. */
    private fun submit(task: () -> Unit) {
        runCatching { calls.execute(task) }
    }

    override fun close() {
        submit {
            synchronized(this) {
                if (claimed && sentPlaying == true) call(TX_PLAY_STATUS) { writeInt(STATUS_PAUSE); writeInt(SOURCE_ONLINE) }
                claimed = false
                closed = true
            }
            if (bound) runCatching { app.unbindService(connection) }
            bound = false
            service = null
        }
        calls.shutdown()
    }

    companion object {
        const val ACTION = "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        const val DESCRIPTOR = "com.ecarx.xui.adaptapi.diminteraction.IDimTransListener"
        // IDimTransListener transactions (extraservice MediaService, IPKServer MediaBean).
        const val TX_PROGRESS = 1 // (currentMillis, durationMillis, unused)
        const val TX_MEDIA_INFO = 2 // (index, title, album, artist, uniqueId[&&&cover], source)
        const val TX_PLAY_STATUS = 3 // (0 pause / 1 play, source)
        const val TX_SOURCE = 5 // (source)
        const val SOURCE_ONLINE = 6
        const val STATUS_PAUSE = 0
        const val STATUS_PLAY = 1
        const val MAX_FAILURE_REPORTS = 5
    }
}
