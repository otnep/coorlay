package com.coorlay.coolray

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.coorlay.UiText.tr
import java.io.File
import java.io.FileOutputStream

/**
 * Background of the home screen and the settings panels: the built-in picture, or one the driver
 * chose. A chosen picture is copied to the app's storage (the USB stick may be removed later),
 * already cropped to the screen, so the head unit never decodes a full-size photo at startup.
 */
internal object CoorlayWallpaper {
    private const val FILE = "wallpaper.jpg"
    private const val TEMPORARY = "$FILE.tmp"
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    /** UI thread only. */
    private var cached: Bitmap? = null
    /** Whether [bitmap] currently shows a picture the driver chose (not the built-in one). UI thread. */
    var showingCustom = false
        private set
    /** An install is writing [TEMPORARY]; startup recovery must not touch it meanwhile. */
    @Volatile private var installing = false

    /** The current background; decoded once and kept until [changed] or [reset]. UI thread. */
    fun bitmap(context: Context): Bitmap? = cached ?: load(context).also { cached = it }

    fun isCustom(context: Context) = File(context.filesDir, FILE).isFile

    fun isImage(file: File) = file.isFile && file.extension.lowercase() in EXTENSIONS

    /**
     * Worker thread. Stores [source] cropped to cover [width]×[height]; throws if it is not a picture
     * Android can read. The current background changes only after [changed] on the UI thread.
     */
    fun install(context: Context, source: File, width: Int, height: Int) {
        installing = true
        try { store(context, source, width, height) } finally { installing = false }
    }

    private fun store(context: Context, source: File, width: Int, height: Int) {
        require(width > 0 && height > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { tr("Android не может прочитать эту картинку", "Android cannot read this picture") }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        val decoded = BitmapFactory.decodeFile(source.path, options)
            ?: throw IllegalArgumentException(tr("Android не может прочитать эту картинку", "Android cannot read this picture"))
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(screen).drawBitmap(decoded, cover(decoded.width, decoded.height, width, height),
                RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        } finally { decoded.recycle() }
        val target = File(context.filesDir, FILE)
        val temporary = File(context.filesDir, TEMPORARY)
        try {
            FileOutputStream(temporary).use {
                check(screen.compress(Bitmap.CompressFormat.JPEG, 92, it)) { tr("Не удалось сохранить картинку", "Could not save the picture") }
                // On disk before the rename: switching the ignition off right after must not lose it.
                it.fd.sync()
            }
        } finally { screen.recycle() }
        // The rename itself may not reach the disk before a power cut; recover() finishes it on the next start.
        check(temporary.renameTo(target)) { tr("Не удалось сохранить картинку", "Could not save the picture") }
    }

    /** A new background was installed: the next draw decodes it. UI thread. */
    fun changed() { cached = null }

    /** Back to the built-in picture. UI thread. */
    fun reset(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, TEMPORARY).delete()
        cached = null
    }

    private fun load(context: Context): Bitmap? {
        if (!installing) recover(context.filesDir)
        val custom = File(context.filesDir, FILE).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        showingCustom = custom != null
        return custom ?: BitmapFactory.decodeResource(context.resources, R.drawable.coorlay_background)
    }

    /**
     * Finishes an install interrupted by a power cut: the picture was synced to [TEMPORARY], but the
     * rename to [FILE] was lost. Only a complete JPEG (ends with the EOI marker) is taken; anything
     * else left behind is a half-written file and is removed.
     */
    internal fun recover(directory: File) {
        val temporary = File(directory, TEMPORARY)
        if (!temporary.isFile) return
        val target = File(directory, FILE)
        if (target.isFile || !completeJpeg(temporary) || !temporary.renameTo(target)) temporary.delete()
    }

    private fun completeJpeg(file: File): Boolean = try {
        java.io.RandomAccessFile(file, "r").use { data ->
            if (data.length() < 4) return false
            val start = ByteArray(2).also { data.readFully(it) }
            data.seek(data.length() - 2)
            val end = ByteArray(2).also { data.readFully(it) }
            start[0] == 0xFF.toByte() && start[1] == 0xD8.toByte() && end[0] == 0xFF.toByte() && end[1] == 0xD9.toByte()
        }
    } catch (_: Exception) { false }

    /** The largest power-of-two reduction that still leaves the picture covering the screen. */
    internal fun sampleSize(imageWidth: Int, imageHeight: Int, width: Int, height: Int): Int {
        var sample = 1
        while (imageWidth / (sample * 2) >= width && imageHeight / (sample * 2) >= height) sample *= 2
        return sample
    }

    /** The centered part of an image that fills a [width]×[height] screen without distortion. */
    internal fun cover(imageWidth: Int, imageHeight: Int, width: Int, height: Int): Rect {
        // Compare aspect ratios without floating point: imageWidth/imageHeight vs width/height.
        return if (imageWidth.toLong() * height > width.toLong() * imageHeight) {
            val cropWidth = (imageHeight.toLong() * width / height).toInt()
            val left = (imageWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, imageHeight)
        } else {
            val cropHeight = (imageWidth.toLong() * height / width).toInt()
            val top = (imageHeight - cropHeight) / 2
            Rect(0, top, imageWidth, top + cropHeight)
        }
    }
}
