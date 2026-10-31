package com.otscup.otsmail.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 后端 createTime 有两种形态：`yyyy-MM-dd HH:mm:ss` 与 13 位毫秒/10 位秒。 */
internal fun timeText(raw: String): String {
    if (raw.isBlank()) return ""
    val plain = raw.filter { it.isDigit() }
    if (plain.length >= 10 && plain.length == raw.length) {
        val n = raw.toLongOrNull() ?: return raw
        val instant = if (n > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    return raw
}

/** 列表右侧只显示当天时刻。 */
internal fun clockOf(raw: String): String {
    val t = timeText(raw)
    return if (t.length >= 16) t.substring(11, 16) else t.takeLast(5)
}
