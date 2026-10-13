package com.coorlay.coolray

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.DexFile
import java.io.File

/** Reads the actual vendor implementation; no guessed binder commands or hardware writes. */
object OemBluetoothDiagnostics {
    const val PACKAGE = "com.neusoft.geely.btphone"
    const val UI_PACKAGE = "bt.ecarxtestn.com.btphoneapp" // KC2_btphoneApp manifest in the factory OTA.

    @Suppress("DEPRECATION")
    fun collect(context: Context): String {
        val lines = mutableListOf<String>()
        try {
            val info = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SERVICES)
            val source = File(info.applicationInfo!!.sourceDir)
            lines += "OEM Bluetooth package=$PACKAGE version=${info.versionName} source=${source.absolutePath} readable=${source.canRead()} bytes=${source.length()}"
            info.services.orEmpty().forEach { lines += "OEM service=${it.name} exported=${it.exported} permission=${it.permission}" }
            try {
                val dex = DexFile(source.absolutePath)
                try {
                    val entries = dex.entries()
                    var count = 0
                    while (entries.hasMoreElements() && count < 150) {
                        val name = entries.nextElement()
                        if (listOf("aidl", "BtManager", "Bluetooth", "IBt", "IClient").any { name.contains(it, true) }) {
                            lines += "OEM Bluetooth class=$name"; count++
                        }
                    }
                } finally { dex.close() }
            } catch (error: Exception) { lines += "OEM dex inspection=${error.javaClass.simpleName}: ${error.message}" }
        } catch (error: Exception) { lines += "OEM Bluetooth diagnostics=${error.javaClass.simpleName}: ${error.message}" }
        try {
            val info = context.packageManager.getPackageInfo("com.anwsdk.service", PackageManager.GET_SERVICES)
            val source = File(info.applicationInfo!!.sourceDir)
            lines += "ANW package=${info.packageName} version=${info.versionName} source=${source.absolutePath} bytes=${source.length()}"
            info.services.orEmpty().forEach { lines += "ANW service=${it.name} exported=${it.exported} permission=${it.permission}" }
        } catch (error: Exception) { lines += "ANW diagnostics=${error.javaClass.simpleName}: ${error.message}" }
        return lines.joinToString("\n")
    }
}
