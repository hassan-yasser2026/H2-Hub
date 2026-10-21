package com.example.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val MAX_PDF_ATTACHMENT_BYTES = 10 * 1024 * 1024

suspend fun encodePdfAttachment(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("تعذر قراءة ملف PDF المحدد.")
        val bytes = stream.use { readBoundedPdf(it) }
        if (bytes.isEmpty()) throw IOException("ملف PDF فارغ.")
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

internal fun readBoundedPdf(
    input: InputStream,
    maxBytes: Int = MAX_PDF_ATTACHMENT_BYTES
): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (total + count > maxBytes) {
            throw IOException("حجم ملف PDF يتجاوز الحد المسموح (10 ميجابايت).")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
