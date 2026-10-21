package com.example.ui

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfAttachmentTest {
    @Test
    fun readsPdfWithinConfiguredLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(bytes, readBoundedPdf(ByteArrayInputStream(bytes), maxBytes = 4))
    }

    @Test
    fun rejectsPdfThatExceedsConfiguredLimit() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))

        assertThrows(IOException::class.java) {
            readBoundedPdf(input, maxBytes = 4)
        }
    }
}
