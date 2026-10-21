package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatImageCompressorTest {
    @Test
    fun copiesAndCompressesSelectedFileUriToServerSizeLimit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pixels = IntArray(900 * 600)
        val random = Random(42)
        pixels.indices.forEach { index ->
            pixels[index] = 0xff000000.toInt() or random.nextInt(0x1000000)
        }
        val bitmap = Bitmap.createBitmap(pixels, 900, 600, Bitmap.Config.ARGB_8888)
        val source = File(context.cacheDir, "vision-test-source.jpg")
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
        assertTrue("Test source should exercise a roughly 500 KB image.", source.length() > 400_000)

        val compressed = compressChatImage(context, Uri.fromFile(source))

        assertTrue(compressed.isFile)
        assertTrue(compressed.length() in 1..1_048_576)
        assertNotNull(BitmapFactory.decodeFile(compressed.absolutePath))
        assertTrue(context.cacheDir.listFiles().orEmpty().none {
            it.name.startsWith("chat-vision-source-")
        })
        source.delete()
        compressed.delete()
    }
}
