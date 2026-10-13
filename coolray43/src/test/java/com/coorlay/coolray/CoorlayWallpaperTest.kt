package com.coorlay.coolray

import android.graphics.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CoorlayWallpaperTest {
    @Test fun photoIsReducedButStillCoversTheScreen() {
        // A 12 MP phone photo on the 1920 × 640 head unit: halving once keeps 2000 ≥ 1920.
        assertEquals(2, CoorlayWallpaper.sampleSize(4000, 3000, 1920, 640))
        // A portrait photo is limited by its width.
        assertEquals(1, CoorlayWallpaper.sampleSize(3000, 4000, 1920, 640))
        assertEquals(4, CoorlayWallpaper.sampleSize(8000, 2600, 1920, 640))
        // A small picture is never shrunk further.
        assertEquals(1, CoorlayWallpaper.sampleSize(800, 600, 1920, 640))
    }

    @Test fun cropKeepsTheCenterInTheScreenProportions() {
        // Wider than the screen: cut the sides.
        assertEquals(Rect(1041, 0, 4959, 1306), CoorlayWallpaper.cover(6000, 1306, 1920, 640))
        // Taller than the screen (4:3 photo on a 3:1 screen): cut top and bottom.
        assertEquals(Rect(0, 833, 4000, 2166), CoorlayWallpaper.cover(4000, 3000, 1920, 640))
        // Same proportions: the whole picture.
        assertEquals(Rect(0, 0, 3840, 1280), CoorlayWallpaper.cover(3840, 1280, 1920, 640))
    }

    @Test fun onlyPictureFilesAreOffered() {
        val folder = Files.createTempDirectory("wallpaper").toFile()
        try {
            val names = listOf("car.JPG", "night.jpeg", "logo.png", "notes.txt", "song.mp3", "photo.webp")
            names.forEach { File(folder, it).writeText("x") }
            File(folder, "pictures.png").mkdir() // A folder named like a picture is still a folder.
            val offered = folder.listFiles().orEmpty().filter(CoorlayWallpaper::isImage).map { it.name }.sorted()
            assertEquals(listOf("car.JPG", "logo.png", "night.jpeg", "photo.webp"), offered)
        } finally { folder.deleteRecursively() }
    }

    @Test fun powerCutAfterSyncKeepsTheChosenPicture() {
        val folder = Files.createTempDirectory("wallpaper").toFile()
        try {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
            // Synced picture, lost rename: taken on the next start.
            File(folder, "wallpaper.jpg.tmp").writeBytes(jpeg)
            CoorlayWallpaper.recover(folder)
            assertArrayEquals(jpeg, File(folder, "wallpaper.jpg").readBytes())
            assertFalse(File(folder, "wallpaper.jpg.tmp").exists())
            // Cut while writing: the half file is dropped, the previous background stays.
            File(folder, "wallpaper.jpg.tmp").writeBytes(jpeg.copyOf(4))
            CoorlayWallpaper.recover(folder)
            assertArrayEquals(jpeg, File(folder, "wallpaper.jpg").readBytes())
            assertFalse(File(folder, "wallpaper.jpg.tmp").exists())
            // Nothing pending: nothing changes.
            CoorlayWallpaper.recover(folder)
            assertTrue(File(folder, "wallpaper.jpg").isFile)
        } finally { folder.deleteRecursively() }
    }
}
