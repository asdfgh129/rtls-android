package com.rtls.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `relative time buckets`() {
        assertEquals("刚刚", RelativeTime.format(0))
        assertEquals("刚刚", RelativeTime.format(4_999))
        assertEquals("12 秒前", RelativeTime.format(12_000))
        assertEquals("3 分钟前", RelativeTime.format(3 * 60_000L))
        assertEquals("2 小时前", RelativeTime.format(2 * 3_600_000L))
        assertEquals("1 天前", RelativeTime.format(24 * 3_600_000L))
    }

    @Test
    fun `future timestamps collapse to just now`() {
        assertEquals("刚刚", RelativeTime.formatSince(2_000, nowMs = 1_000))
    }

    @Test
    fun `coordinates keep six decimal places`() {
        assertEquals("116.407400", formatCoordinate(116.4074))
        assertEquals("-0.127800", formatCoordinate(-0.1278))
    }

    @Test
    fun `accuracy handles missing values`() {
        assertEquals("未知", formatAccuracy(0f))
        assertEquals("15 m", formatAccuracy(15.4f))
    }
}
