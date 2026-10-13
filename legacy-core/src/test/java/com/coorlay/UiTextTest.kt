package com.coorlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UiTextTest {
    private val original = Locale.getDefault()

    @After fun restore() = Locale.setDefault(original)

    @Test fun russianSystemShowsRussian() {
        Locale.setDefault(Locale("ru", "RU"))
        assertEquals("Настройки", UiText.tr("Настройки", "Settings"))
    }

    @Test fun everyOtherSystemShowsEnglish() {
        for (locale in listOf(Locale.US, Locale.UK, Locale.GERMANY, Locale.CHINA, Locale("uk", "UA"))) {
            Locale.setDefault(locale)
            assertEquals("Settings", UiText.tr("Настройки", "Settings"))
        }
    }

    @Test fun languageChangeAppliesToTheNextText() {
        Locale.setDefault(Locale.US)
        assertEquals("Settings", UiText.tr("Настройки", "Settings"))
        Locale.setDefault(Locale("ru"))
        assertEquals("Настройки", UiText.tr("Настройки", "Settings"))
    }
}
