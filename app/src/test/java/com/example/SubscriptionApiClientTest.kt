package com.example

import com.example.network.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubscriptionApiClientTest {
    @Test
    fun plansMatchContract() {
        assertEquals(listOf(50, 130, 400), SubscriptionPlan.entries.map { it.priceEgp })
        assertEquals(listOf(30, 90, 365), SubscriptionPlan.entries.map { it.days })
        assertEquals(listOf("monthly", "quarterly", "yearly"), SubscriptionPlan.entries.map { it.apiValue })
    }

    @Test
    fun parsesStatus() {
        val s = parseSubscriptionStatus(
            """{"user":{"userCode":"U1","vodafoneNumber":"01012345678","subscriptionPlan":"monthly","subscriptionEndDate":"2026-01-01"},
            "subscription":{"status":"active","plan":"monthly","startDate":"a","endDate":"b","daysRemaining":12},
            "requests":[{"id":"r1","plan":"yearly","amount":400,"uniqueCode":"C","status":"rejected","rejectionReason":null,"createdAt":"t"}]}"""
        )
        assertEquals("U1", s.user.userCode)
        assertEquals(12, s.subscription.daysRemaining)
        assertEquals("", s.requests[0].rejectionReason)
        assertEquals(400, s.requests[0].amount)
    }

    @Test
    fun parsesCodeAndErrors() {
        assertEquals("R", parsePaymentCode("""{"requestId":"R","uniqueCode":"X","amount":50,"plan":"monthly"}""").requestId)
        assertEquals("bad", extractSubscriptionError(400, """{"error":"bad"}"""))
        assertEquals("فشل الطلب (HTTP 500).", extractSubscriptionError(500, "oops"))
        assertTrue(isValidVodafoneNumber("01012345678"))
        assertFalse(isValidVodafoneNumber("0212345"))
    }

    @Test
    fun parsesServerSubscriptionPlans() {
        val plans = parseSubscriptionPlans(
            """{"plans":[{"name":"trial","nameAr":"تجريبي","price":100,"currency":"EGP","durationDays":30,"messages":500,"features":{"search":true,"thinking":true,"images":false}},{"name":"basic","nameAr":"أساسي","price":1000,"currency":"EGP","durationDays":30,"messages":5000,"features":{"images":true}},{"name":"premium","nameAr":"مميز","price":2000,"currency":"EGP","durationDays":30,"messages":15000,"features":{"priority":true}},{"name":"annual","nameAr":"سنوي","price":10000,"currency":"EGP","durationDays":365,"messages":100000,"features":{"support":true}},{"name":"annual_premium","nameAr":"سنوي مميز","price":20000,"currency":"EGP","durationDays":365,"messages":300000,"features":{"vip":true}}]}"""
        )
        assertEquals(5, plans.size)
        assertEquals(listOf(100, 1_000, 2_000, 10_000, 20_000), plans.map { it.price })
        assertEquals(listOf(30, 30, 30, 365, 365), plans.map { it.durationDays })
        assertEquals(listOf(500, 5_000, 15_000, 100_000, 300_000), plans.map { it.messages })
        assertEquals("trial", plans.first().name)
        assertFalse(plans.first().features["images"]!!)
        assertTrue(plans[1].features["images"]!!)
    }
}
