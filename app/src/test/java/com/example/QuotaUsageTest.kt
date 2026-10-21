package com.example

import com.example.network.parseQuotaError
import com.example.network.parseUsageSnapshot
import com.example.ui.UsageLevel
import com.example.ui.UsageIndicatorIcon
import com.example.ui.UsageState
import com.example.ui.usageBanner
import com.example.ui.usageIndicatorModel
import com.example.ui.requiredMessageQuota
import com.example.ui.VODAFONE_CASH_NUMBER
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuotaUsageTest {
    private val free = """{"paid":false,"legacy":false,"subscription":null,"usage":{"chat":{"used":18,"limit":20},"coder":{"used":2,"limit":20},"quran":{"used":0,"limit":0}}}"""

    @Test fun parsesUsage() {
        val s = parseUsageSnapshot(free)
        assertFalse(s.paid)
        assertEquals(2, s.feature("chat")!!.remaining)
        assertNull(s.feature("vision"))
    }

    @Test fun warnsAtThreeOrLess() {
        val b = usageBanner(UsageState(parseUsageSnapshot(free)))!!
        assertTrue(b.warning); assertFalse(b.exhausted)
        assertTrue(b.text.contains("باقي 2 رسائل"))
        val ok = parseUsageSnapshot("""{"paid":false,"legacy":false,"usage":{"chat":{"used":1,"limit":10},"coder":{"used":1,"limit":10}}}""")
        assertFalse(usageBanner(UsageState(ok))!!.warning)
    }

    @Test fun paidIsUnlimited() {
        val snapshot = parseUsageSnapshot("""{"paid":true,"legacy":true,"subscription":null,"usage":{"chat":{"used":0,"limit":0}}}""")
        val b = usageBanner(UsageState(snapshot))!!
        assertTrue(snapshot.legacy)
        assertEquals("بدون حدود ✨", b.text)
        assertFalse(b.warning)
    }

    @Test fun meteredPlanShowsItsRemainingCreditAndWarnings() {
        val snapshot = parseUsageSnapshot(
            """{"paid":true,"legacy":false,"subscription":{"plan":"trial","nameAr":"تجريبي","used":490,"limit":500,"remaining":10,"expiresAt":"2026-11-05T00:00:00.000Z","features":{"images":false}},"usage":{"chat":{"used":490,"limit":500}}}"""
        )
        assertFalse(snapshot.legacy)
        assertEquals(10, snapshot.subscription?.remaining)
        val banner = usageBanner(UsageState(snapshot))!!
        assertEquals(UsageLevel.CRITICAL, banner.level)
        assertTrue(banner.text.contains("باقي 10"))
    }

    @Test fun compactIndicatorDistinguishesFreePaidLegacyAndExhaustedBalances() {
        val freeSnapshot = parseUsageSnapshot(free)
        val freeIndicator = usageIndicatorModel(freeSnapshot)!!
        assertEquals(UsageIndicatorIcon.FREE, freeIndicator.icon)
        assertEquals("2/20", freeIndicator.value)
        assertEquals(UsageLevel.WARNING, freeIndicator.level)

        val paidSnapshot = parseUsageSnapshot(
            """{"paid":true,"legacy":false,"subscription":{"plan":"basic","nameAr":"أساسي","used":0,"limit":5000,"remaining":5000,"expiresAt":"2026-11-05T00:00:00.000Z","features":{"images":true}},"usage":{"chat":{"used":0,"limit":5000}}}"""
        )
        val paidIndicator = usageIndicatorModel(paidSnapshot)!!
        assertEquals(UsageIndicatorIcon.PAID, paidIndicator.icon)
        assertEquals("5000", paidIndicator.value)
        assertEquals("أساسي", paidIndicator.planName)

        val legacySnapshot = parseUsageSnapshot(
            """{"paid":true,"legacy":true,"subscription":null,"usage":{"chat":{"used":0,"limit":0}}}"""
        )
        val legacyIndicator = usageIndicatorModel(legacySnapshot)!!
        assertEquals(UsageIndicatorIcon.LEGACY, legacyIndicator.icon)
        assertEquals("∞", legacyIndicator.value)

        val exhaustedSnapshot = parseUsageSnapshot(
            """{"paid":false,"legacy":false,"usage":{"chat":{"used":20,"limit":20}}}"""
        )
        assertEquals(UsageLevel.EXHAUSTED, usageIndicatorModel(exhaustedSnapshot)?.level)
    }

    @Test fun quotaErrorOnlyFor429WithContract() {
        val body = """{"error":"انتهت","feature":"chat","used":10,"limit":10,"resetAt":"2026-01-01"}"""
        val e = parseQuotaError(429, body)!!
        assertEquals("chat", e.feature); assertEquals("انتهت", e.message)
        assertNull(parseQuotaError(500, body))
        assertNull(parseQuotaError(429, """{"error":"rate"}"""))
        assertNull(parseQuotaError(429, "not json"))
    }

    @Test fun exhaustedUpdatesState() {
        val s = UsageState(parseUsageSnapshot(free)).withExhausted("chat", 10, 10)
        assertTrue(usageBanner(s)!!.exhausted)
    }

    @Test fun operationCostsMatchServerQuotaPolicy() {
        assertEquals(1, requiredMessageQuota())
        assertEquals(2, requiredMessageQuota(webSearch = true))
        assertEquals(2, requiredMessageQuota(thinking = true))
        assertEquals(3, requiredMessageQuota(webSearch = true, thinking = true))
        assertEquals(3, requiredMessageQuota(vision = true))
        assertEquals(20, requiredMessageQuota(imageGeneration = true))
    }

    @Test fun paymentNumber() = assertEquals("01067254988", VODAFONE_CASH_NUMBER)
}
