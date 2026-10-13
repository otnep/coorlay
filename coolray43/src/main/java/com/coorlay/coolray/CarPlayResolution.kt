package com.coorlay.coolray

import kotlin.math.min

/** Pixel sizes, independent of density. Keep the factory size as an explicit fallback. */
internal object CarPlayResolution {
    data class Size(val width: Int, val height: Int) {
        init { require(width > 0 && height > 0) }
        override fun toString() = "${width}x$height"
    }
    data class Selection(val viewport: Size, val base: Size, val video: Size,
        val automatic: Boolean, val reduced: Boolean) {
        fun diagnostic() = "DISPLAY viewport=$viewport mode=${if (automatic) "auto" else "fixed"} " +
            "base=$base video=$video reduced=$reduced limit=$MAX_WIDTH" + "x$MAX_HEIGHT fps=30"
    }

    val FACTORY = Size(1920, 640)
    // Do not request a 4K stream from the API-18 media backend. A decoder may still
    // need the reduced-load option; this is a request ceiling, not a capability claim.
    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080

    fun select(width: Int, height: Int, automatic: Boolean = true, reduced: Boolean = false): Selection {
        val viewport = if (width >= 2 && height >= 2) Size(width, height) else FACTORY
        val requested = if (automatic) viewport else FACTORY
        val factor = min(1.0, min(MAX_WIDTH.toDouble() / requested.width, MAX_HEIGHT.toDouble() / requested.height))
        val base = scaled(requested, factor)
        val video = if (reduced) scaled(base, 27.0 / 40.0) else base
        return Selection(viewport, base, video, automatic, reduced)
    }

    /** Fit the frozen session canvas into the current window without stretching. */
    fun fit(viewport: Size, video: Size): Size {
        val width = min(viewport.width.toLong(), viewport.height.toLong() * video.width / video.height)
            .coerceAtLeast(1).toInt()
        val height = min(viewport.height.toLong(), width.toLong() * video.height / video.width)
            .coerceAtLeast(1).toInt()
        return Size(width, height)
    }

    private fun scaled(size: Size, factor: Double) = Size(even(size.width * factor), even(size.height * factor))
    private fun even(value: Double) = (value.toInt() / 2 * 2).coerceAtLeast(2)
}
