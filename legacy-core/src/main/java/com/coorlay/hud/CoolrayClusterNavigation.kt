package com.coorlay.hud

import android.content.Context
import android.content.Intent
import com.coorlay.iap2.wire.Iap2Frame
import java.io.Closeable

/**
 * CarPlay turn-by-turn on the Coolray instrument cluster.
 *
 * The Geely SX11 cluster arrow is fed by the AMap Auto "standard broadcast" protocol, the same one
 * the Community mod uses for Yandex Navigator: KEY_TYPE 10019 starts/stops guidance and KEY_TYPE
 * 10001 carries the maneuver icon, distance and road; ecarx.navi.UPDATE_* feed the XCNaviService
 * widget. The iPhone's iAP2 route guidance (0x5201/0x5202) is decoded by [BydHudRouteState].
 */
class CoolrayClusterNavigation(
    context: Context,
    private val report: (String) -> Unit = {},
) : Closeable {
    private val app = context.applicationContext
    private val route = BydHudRouteState()
    private var guiding = false
    private var lastSent: Guidance? = null
    private var lastSentAt = 0L
    private var lastReportAt = 0L
    private var framesReceived = 0
    private var lastRxReportAt = 0L

    private data class Guidance(val icon: Int, val distance: Int, val road: String, val remainMeters: Int, val remainSeconds: Int)

    @Synchronized
    fun accept(frame: Iap2Frame) {
        val change = route.accept(frame.messageId, frame.payload)
        framesReceived++
        // Wired sessions do not dump iAP2 frames, so show here that the iPhone sends route guidance at all.
        val now = System.currentTimeMillis()
        if (framesReceived <= RX_DUMP_FRAMES || now - lastRxReportAt >= REPORT_MS) {
            val hex = frame.payload.take(RX_DUMP_BYTES).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
            report("CLUSTER rx #$framesReceived 0x${Integer.toHexString(frame.messageId)} bytes=${frame.payload.size} " +
                "change=$change maneuver=${route.currentApple()?.type} [$hex]")
            lastRxReportAt = now
        }
        publish()
    }

    /** Called about once a second: hides a stale route and refreshes the cluster. */
    @Synchronized
    fun tick() = publish()

    @Synchronized
    override fun close() {
        route.clear()
        if (guiding) stop()
    }

    private fun publish() {
        val maneuver = route.currentApple()
        if (maneuver == null) {
            if (guiding) stop()
            return
        }
        val icon = amapIcon(maneuver.type)
        if (icon == 0) return // Unknown maneuver: keep the previous arrow.
        val guidance = Guidance(
            icon = icon,
            distance = maneuver.distanceMeters.coerceAtLeast(0),
            road = shortRoad(maneuver.road),
            remainMeters = maneuver.remainingMeters?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: 1,
            remainSeconds = maneuver.remainingSeconds?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: 1,
        )
        if (!guiding) start()
        val now = System.currentTimeMillis()
        if (guidance == lastSent && now - lastSentAt < REFRESH_MS) return
        if (guidance.icon != lastSent?.icon || guidance.road != lastSent?.road || now - lastReportAt >= REPORT_MS) {
            report("CLUSTER maneuver apple=${maneuver.type} icon=${guidance.icon} dist=${guidance.distance} road=${guidance.road} " +
                "remain=${guidance.remainMeters}m/${guidance.remainSeconds}s")
            lastReportAt = now
        }
        lastSent = guidance
        lastSentAt = now
        // The Community mod repeats the start state before every maneuver. The firmware IPKServer drops
        // KEY_TYPE 10001 unless it has seen a "started" 10019 since it (re)started, so a single start
        // sent before the cluster service came up would leave the cluster blank for the whole route.
        sendState(STATE_GUIDING, WIDGET_STATE_GUIDING)
        send(Intent(AMAP_ACTION)
            .putExtra("KEY_TYPE", KEY_GUIDE_INFO)
            .putExtra("ICON", guidance.icon)
            .putExtra("SEG_REMAIN_DIS", guidance.distance)
            .putExtra("ROUTE_REMAIN_DIS", guidance.remainMeters)
            .putExtra("ROUTE_REMAIN_TIME", guidance.remainSeconds)
            .putExtra("NEXT_ROAD_NAME", guidance.road))
        send(Intent(WIDGET_GUIDE_ACTION).putExtra("icon", guidance.icon).putExtra("distance", guidance.distance))
    }

    private fun start() {
        guiding = true
        report("CLUSTER guidance started")
        sendState(STATE_GUIDING, WIDGET_STATE_GUIDING)
    }

    private fun stop() {
        guiding = false
        lastSent = null
        report("CLUSTER guidance stopped")
        sendState(STATE_STOPPED, WIDGET_STATE_STOPPED)
    }

    private fun sendState(state: Int, widgetState: Int) {
        send(Intent(AMAP_ACTION).putExtra("KEY_TYPE", KEY_NAVI_STATE).putExtra("EXTRA_STATE", state))
        send(Intent(WIDGET_STATE_ACTION).putExtra("naviState", widgetState))
    }

    private fun send(intent: Intent) {
        try {
            app.sendBroadcast(intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        } catch (error: Exception) {
            report("CLUSTER broadcast failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object {
        private const val RX_DUMP_FRAMES = 6
        private const val RX_DUMP_BYTES = 48
        private const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        private const val WIDGET_GUIDE_ACTION = "ecarx.navi.UPDATE_GUIDEINFO"
        private const val WIDGET_STATE_ACTION = "ecarx.navi.UPDATE_STATUS"
        private const val KEY_GUIDE_INFO = 10001
        private const val KEY_NAVI_STATE = 10019
        // Values the Community mod sends for Yandex Navigator on this cluster.
        private const val STATE_GUIDING = 10
        private const val STATE_STOPPED = 12
        private const val WIDGET_STATE_GUIDING = 16
        private const val WIDGET_STATE_STOPPED = 17
        private const val REFRESH_MS = 2_000L
        private const val REPORT_MS = 15_000L

        /**
         * Apple iAP2 RouteGuidanceManeuverType -> cluster ICON, using the codes the Community mod
         * proved on the SX11 cluster: 1 straight, 2 left, 3 right, 4/5 slight left/right,
         * 6/7 sharp left/right, 8 U-turn, 11 enter roundabout, 12 leave roundabout, 15 destination.
         * 0 means "no arrow for this maneuver".
         */
        fun amapIcon(appleType: Int): Int = when (appleType) {
            1, 20 -> 2
            2, 21 -> 3
            13, 22, 49, 52 -> 4
            14, 23, 50, 53 -> 5
            47 -> 6
            48 -> 7
            4, 18, 19, 26 -> 8
            6, in 28..46 -> 11
            7 -> 12
            10, 12, 24, 25, 27 -> 15
            3, 5, 8, 9, 11, 51 -> 1
            else -> 0
        }

        private val ABBREVIATIONS = listOf(
            "улица" to "ул.", "проспект" to "пр-т", "проезд" to "пр.", "переулок" to "пер.",
            "шоссе" to "ш.", "площадь" to "пл.", "бульвар" to "б-р", "набережная" to "наб.",
        )

        /** The cluster line is short; abbreviate Russian street types like the Community mod does. */
        fun shortRoad(road: String): String =
            ABBREVIATIONS.fold(road.trim()) { name, (long, short) -> name.replace(long, short, ignoreCase = true) }
    }
}
