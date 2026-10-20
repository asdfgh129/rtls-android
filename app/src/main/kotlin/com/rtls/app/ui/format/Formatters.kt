package com.rtls.app.ui.format

import java.util.Locale

object RelativeTime {

    fun format(elapsedMs: Long): String = when {
        elapsedMs < 5_000 -> "刚刚"
        elapsedMs < 60_000 -> "${elapsedMs / 1_000} 秒前"
        elapsedMs < 3_600_000 -> "${elapsedMs / 60_000} 分钟前"
        elapsedMs < 86_400_000 -> "${elapsedMs / 3_600_000} 小时前"
        else -> "${elapsedMs / 86_400_000} 天前"
    }

    fun formatSince(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        format((nowMs - timestampMs).coerceAtLeast(0))
}

fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

fun formatAccuracy(meters: Float): String =
    if (meters <= 0f) "未知" else String.format(Locale.US, "%.0f m", meters)
