package com.example.network

private const val VISION_IMAGE_MARKER = "[[H2_VISION_IMAGE:"
private const val VISION_IMAGE_MARKER_END = "]]"

data class ChatVisionAttachment(
    val imagePath: String,
    val question: String
)

fun encodeChatVisionMessage(imagePath: String, question: String): String =
    "$VISION_IMAGE_MARKER$imagePath$VISION_IMAGE_MARKER_END\n${question.trim()}"

fun parseChatVisionMessage(content: String): ChatVisionAttachment? {
    if (!content.startsWith(VISION_IMAGE_MARKER)) return null
    val markerEnd = content.indexOf(VISION_IMAGE_MARKER_END, VISION_IMAGE_MARKER.length)
    if (markerEnd < 0) return null
    val imagePath = content.substring(VISION_IMAGE_MARKER.length, markerEnd)
    if (imagePath.isBlank()) return null
    return ChatVisionAttachment(
        imagePath = imagePath,
        question = content.substring(markerEnd + VISION_IMAGE_MARKER_END.length)
            .trimStart('\n', '\r')
    )
}
