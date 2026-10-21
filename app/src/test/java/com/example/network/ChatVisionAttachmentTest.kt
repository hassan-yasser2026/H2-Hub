package com.example.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatVisionAttachmentTest {
    @Test
    fun parsesStoredImagePathAndQuestion() {
        val encoded = encodeChatVisionMessage("/data/user/0/example/files/question.jpg", "حل دي")

        assertEquals(
            ChatVisionAttachment(
                imagePath = "/data/user/0/example/files/question.jpg",
                question = "حل دي"
            ),
            parseChatVisionMessage(encoded)
        )
    }

    @Test
    fun imageWithoutCaptionCanBeParsed() {
        val encoded = encodeChatVisionMessage("/tmp/photo.jpg", "")

        assertEquals("", parseChatVisionMessage(encoded)?.question)
    }

    @Test
    fun regularMessagesAreNotVisionAttachments() {
        assertNull(parseChatVisionMessage("رسالة عادية"))
        assertNull(parseChatVisionMessage("[[H2_VISION_IMAGE:]]\nحل دي"))
    }
}
