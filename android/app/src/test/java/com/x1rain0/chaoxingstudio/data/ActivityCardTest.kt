package com.x1rain0.chaoxingstudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCardTest {
    private fun card(startedAtMillis: Long) = ActivityCard(
        activeId = "test-active",
        name = "测试签到",
        kind = 0,
        kindName = "普通签到",
        courseTitle = "测试课程",
        startedAtMillis = startedAtMillis,
        rawJson = "{}",
    )

    @Test
    fun unknownStartTimeUsesDiscoveryLabel() {
        assertEquals("刚发现", card(0L).freshness(nowMillis = 60_000L))
        assertEquals("刚发现", card(-1L).freshness(nowMillis = 60_000L))
    }

    @Test
    fun futureStartTimeDoesNotShowNegativeAge() {
        assertEquals("刚开始", card(130_000L).freshness(nowMillis = 100_000L))
    }

    @Test
    fun elapsedAgeIsRoundedDownToWholeMinutes() {
        assertEquals("刚开始", card(40_001L).freshness(nowMillis = 100_000L))
        assertEquals("已开始 1 分钟", card(40_000L).freshness(nowMillis = 100_000L))
        assertEquals("已开始 1 分钟", card(1L).freshness(nowMillis = 120_000L))
    }

    @Test
    fun signedFlagCanDisableFurtherActions() {
        assertFalse(card(1L).signed)
        assertTrue(card(1L).copy(signed = true).signed)
    }
}
