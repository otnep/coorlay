package com.coorlay.coolray

import com.coorlay.UiText.tr

/** Neusoft's API-18 WifiConfiguration adds maxNumSta; its constructor defaults to zero. */
object OemSoftApConfiguration {
    const val STATION_LIMIT = 5 // Same limit as the factory WifiApConfigStore default.

    fun stationLimit(configuration: Any): Int? = try {
        configuration.javaClass.getField("maxNumSta").getInt(configuration)
    } catch (_: NoSuchFieldException) { null }

    fun applyStationLimit(configuration: Any): Int? = try {
        configuration.javaClass.getField("maxNumSta").apply {
            setInt(configuration, STATION_LIMIT)
            check(getInt(configuration) == STATION_LIMIT) { tr("Не удалось задать лимит клиентов точки Neusoft", "Could not set the Neusoft hotspot client limit") }
        }
        STATION_LIMIT
    } catch (_: NoSuchFieldException) { null }

    fun verifyStationLimit(configuration: Any) {
        stationLimit(configuration)?.let {
            check(it > 0) { tr("Android сохранил лимит клиентов точки Neusoft=$it; подключения будут отклонены", "Android saved Neusoft hotspot client limit=$it; connections will be refused") }
        }
    }
}
