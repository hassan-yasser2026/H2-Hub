package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

private const val MAX_COMPRESSED_IMAGE_BYTES = 1_048_576
private const val MAX_IMAGE_EDGE = 2048
private const val MAX_SOURCE_IMAGE_BYTES = 64L * 1024 * 1024

fun compressChatImage(context: Context, uri: Uri): File {
    val sourceFile = File.createTempFile("chat-vision-source-", ".img", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("تعذر فتح الصورة من المعرض. اخترها مرة أخرى.")
        input.use { source ->
            FileOutputStream(sourceFile).use { destination ->
                val buffer = ByteArray(64 * 1024)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_SOURCE_IMAGE_BYTES) {
                        "حجم الصورة الأصلية كبير جداً؛ اختر صورة أصغر."
                    }
                    destination.write(buffer, 0, count)
                }
                check(totalBytes > 0) { "الصورة المحددة فارغة." }
            }
        }

        return compressImageFile(context, sourceFile)
    } finally {
        if (sourceFile.exists() && !sourceFile.delete()) {
            Log.w("ChatImageCompressor", "Unable to delete the temporary source image.")
        }
    }
}

private fun compressImageFile(context: Context, sourceFile: File): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "ملف الصورة غير صالح." }

    var sampleSize = 1
    while (
        max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_IMAGE_EDGE
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
        ?: error("تعذر فك ترميز الصورة. اختر صورة بصيغة مدعومة وحاول مرة أخرى.")
    val rotation = ExifInterface(sourceFile.absolutePath).rotationDegrees
    if (rotation != 0) {
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true
        )
        if (rotated !== bitmap) bitmap.recycle()
        bitmap = rotated
    }

    try {
        var quality = 88
        var attempts = 0
        var compressed: ByteArray
        while (true) {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "تعذر تحويل الصورة إلى صيغة قابلة للإرسال."
                }
                compressed = output.toByteArray()
            }
            if (compressed.size <= MAX_COMPRESSED_IMAGE_BYTES) break
            attempts++
            if (quality > 52) {
                quality -= 12
            } else if (attempts < 12) {
                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * 0.8f).toInt().coerceAtLeast(1),
                    (bitmap.height * 0.8f).toInt().coerceAtLeast(1),
                    true
                )
                if (resized !== bitmap) bitmap.recycle()
                bitmap = resized
                quality = 82
            } else {
                error("تعذر ضغط الصورة إلى أقل من 1 ميجابايت.")
            }
        }

        val directory = File(context.filesDir, "chat-vision").apply {
            check(mkdirs() || isDirectory) { "تعذر تجهيز مساحة حفظ الصورة." }
        }
        return File(directory, "${UUID.randomUUID()}.jpg").also { destination ->
            FileOutputStream(destination).use { output ->
                output.write(compressed)
                output.flush()
            }
        }
    } finally {
        bitmap.recycle()
    }
}

fun deleteChatVisionImage(context: Context, imagePath: String) {
    try {
        val directory = File(context.filesDir, "chat-vision").canonicalFile
        val image = File(imagePath).canonicalFile
        if (image.parentFile != directory || image.extension != "jpg") return
        if (image.exists() && !image.delete()) {
            Log.w("ChatImageCompressor", "Unable to delete an unused chat image.")
        }
    } catch (error: IOException) {
        Log.w("ChatImageCompressor", "Unable to resolve a chat image for cleanup.", error)
    }
}
