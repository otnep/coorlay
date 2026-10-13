package com.coorlay.coolray

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.test.AndroidTestCase
import com.coorlay.hud.CoolrayClusterMedia
import com.coorlay.media.CarPlayNowPlaying
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClusterMediaTest : AndroidTestCase() {
    fun testCarPlayTrackReachesTheClusterRelayAsAnOnlineSource() {
        val relay = bindRelay()
        var yielded = false
        val media = CoolrayClusterMedia(context, { yielded })
        try {
            Thread.sleep(500)
            dump(relay)
            media.update(CarPlayNowPlaying(title = "Кино", artist = "Группа крови", album = "Группа крови",
                durationMillis = 284_000, elapsedMillis = 10_000, playing = true))
            Thread.sleep(300)
            assertEquals(listOf(
                "source 6",
                "info 0 Кино|Группа крови|Группа крови|carplay1&&&http:none 6",
                "status 1 6",
            ), dump(relay))

            media.tick()
            Thread.sleep(300)
            val progress = dump(relay)
            assertEquals(1, progress.size)
            assertTrue(progress.single(), progress.single().matches(Regex("progress 1\\d{4} 284000 null")))

            // The elapsed time alone changes nothing on the cluster but the progress.
            media.update(CarPlayNowPlaying(title = "Кино", artist = "Группа крови", album = "Группа крови",
                durationMillis = 284_000, elapsedMillis = 10_500, playing = true))
            media.update(CarPlayNowPlaying(title = "Кукушка", artist = "Кино", album = null,
                durationMillis = 400_000, elapsedMillis = 0, playing = true))
            media.update(CarPlayNowPlaying(title = "Кукушка", artist = "Кино", album = null,
                durationMillis = 400_000, elapsedMillis = 0, playing = false))
            Thread.sleep(300)
            // A new track restates the play status, which the cluster may reset with the track.
            assertEquals(listOf("info 0 Кукушка| |Кино|carplay2&&&http:none 6", "status 1 6", "status 0 6"), dump(relay))

            // MODE to the radio: the radio owns the cluster, CarPlay announces itself again when it plays.
            yielded = true
            media.update(CarPlayNowPlaying(title = "Кукушка", artist = "Кино", playing = true))
            Thread.sleep(300)
            assertEquals(emptyList<String>(), dump(relay))
            yielded = false
            media.tick()
            Thread.sleep(300)
            assertEquals(listOf("source 6", "info 0 Кукушка| |Кино|carplay3&&&http:none 6", "status 1 6"), dump(relay))
        } finally {
            media.close()
        }
        Thread.sleep(300)
        assertEquals(listOf("status 0 6"), dump(relay))
    }

    private fun bindRelay(): IBinder {
        val ready = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent(CoolrayClusterMedia.ACTION), connection, Context.BIND_AUTO_CREATE))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return binder!!
    }

    private fun dump(relay: IBinder): List<String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            relay.transact(FakeDimMediaService.DUMP, data, reply, 0)
            return ArrayList<String>().also { reply.readStringList(it) }
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}
