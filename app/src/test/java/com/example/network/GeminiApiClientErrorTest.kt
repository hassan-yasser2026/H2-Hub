package com.example.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiApiClientErrorTest {
    @Test
    fun translatesCommonTransportAndHttpErrorsToArabic() {
        assertEquals(
            "😅 حدثت مشكلة بسيطة. حاول مرة أخرى.",
            GeminiApiClient.friendlyErrorMessage(IOException("HTTP 404 API route not found"))
        )
        assertEquals(
            "😅 هذه الميزة غير متاحة حالياً. حاول بعد قليل.",
            GeminiApiClient.friendlyErrorMessage(IOException("HTTP 500 Server Error"))
        )
        assertEquals(
            "🔧 نجري صيانة سريعة. حاول مرة أخرى بعد قليل.",
            GeminiApiClient.friendlyErrorMessage(IOException("HTTP 503 Service Unavailable"))
        )
        assertEquals(
            "⏱️ تأخرت الاستجابة. حاول مرة أخرى.",
            GeminiApiClient.friendlyErrorMessage(SocketTimeoutException("Request Timeout"))
        )
        assertEquals(
            "📶 تحقق من اتصالك بالإنترنت وحاول مرة أخرى.",
            GeminiApiClient.friendlyErrorMessage(UnknownHostException("Failed to connect"))
        )
        assertEquals(
            "🕐 انتظر قليلاً ثم حاول مرة أخرى.",
            GeminiApiClient.friendlyErrorMessage(IOException("HTTP 429 Too Many Requests"))
        )
    }

    @Test
    fun preservesFriendlyArabicServiceMessagesAndHidesTechnicalMessages() {
        assertEquals(
            "ميزة توليد الصور متوقفة مؤقتاً للصيانة.",
            GeminiApiClient.friendlyErrorMessage(
                IOException("ميزة توليد الصور متوقفة مؤقتاً للصيانة.")
            )
        )
        assertEquals(
            "😅 حدثت مشكلة. حاول مرة أخرى، أو تواصل معنا إذا استمرت.",
            GeminiApiClient.friendlyErrorMessage(IOException("database socket failed"))
        )
    }

    @Test
    fun preservesActionableArabicIdentityConflictFromServer() {
        val serverMessage = GeminiApiClient.parseServerErrorMessage(
            """{"error":"هذا الجهاز مرتبط بحساب آخر. سجّل الدخول بالحساب المرتبط به."}"""
        )
        assertEquals(
            "هذا الجهاز مرتبط بحساب آخر. سجّل الدخول بالحساب المرتبط به.",
            GeminiApiClient.friendlyErrorMessage(409, serverMessage)
        )
    }
}
