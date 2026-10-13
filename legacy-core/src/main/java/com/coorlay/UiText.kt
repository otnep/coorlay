package com.coorlay

import java.util.Locale

/**
 * coorlay's interface language: Russian on a head unit set to Russian, English on every other system.
 * The texts stay next to the code that shows them, as before; the locale is read on each call, so a
 * language change in Android settings applies to the next message.
 */
object UiText {
    val russian: Boolean get() = Locale.getDefault().language == "ru"

    /** [ru] on a Russian system, otherwise [en]. */
    fun tr(ru: String, en: String): String = if (russian) ru else en
}
