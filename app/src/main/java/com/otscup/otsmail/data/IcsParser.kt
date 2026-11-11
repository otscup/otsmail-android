package com.otscup.otsmail.data

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * iCalendar (RFC 5545) 简易解析器。
 * 针对常见的会议邀请（Google Meet、Outlook、Zoom、飞书等）提取关键日程信息，
 * 并支持通过系统 Intent 直接写入系统日历（无需声明日历读写敏感权限）。
 */
object IcsParser {

    data class Event(
        val summary: String,
        val startTimeMillis: Long,
        val endTimeMillis: Long,
        val location: String = "",
        val description: String = "",
        val allDay: Boolean = false,
    )

    /**
     * 解析 ICS 文本内容。如果未找到有效 VEVENT，返回 null。
     */
    fun parse(raw: String): Event? {
        if (raw.isBlank() || !raw.contains("BEGIN:VEVENT", ignoreCase = true)) {
            return null
        }

        // 处理 iCalendar 的折行（以空格或制表符开头的行是上一行的延续）
        val unfolded = raw.replace(Regex("\r?\n[ \t]"), "")
        val lines = unfolded.lines()

        var inEvent = false
        var summary = ""
        var location = ""
        var description = ""
        var dtStartRaw = ""
        var dtEndRaw = ""
        var durationRaw = ""

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inEvent = true
                continue
            }
            if (trimmed.equals("END:VEVENT", ignoreCase = true)) {
                break
            }
            if (!inEvent) continue

            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) continue

            val propPart = trimmed.substring(0, colonIdx).uppercase()
            val valuePart = unescape(trimmed.substring(colonIdx + 1).trim())

            when {
                propPart == "SUMMARY" || propPart.startsWith("SUMMARY;") -> {
                    summary = valuePart
                }
                propPart == "LOCATION" || propPart.startsWith("LOCATION;") -> {
                    location = valuePart
                }
                propPart == "DESCRIPTION" || propPart.startsWith("DESCRIPTION;") -> {
                    description = valuePart
                }
                propPart == "DTSTART" || propPart.startsWith("DTSTART;") -> {
                    dtStartRaw = trimmed.substring(colonIdx + 1).trim()
                }
                propPart == "DTEND" || propPart.startsWith("DTEND;") -> {
                    dtEndRaw = trimmed.substring(colonIdx + 1).trim()
                }
                propPart == "DURATION" || propPart.startsWith("DURATION;") -> {
                    durationRaw = valuePart
                }
            }
        }

        if (summary.isBlank() && dtStartRaw.isBlank()) {
            return null
        }

        val (startMs, allDay) = parseDate(dtStartRaw)
        var (endMs, _) = parseDate(dtEndRaw)

        // 若无结束时间，但有持续时间或默认 1 小时
        if (endMs <= 0 && startMs > 0) {
            endMs = if (durationRaw.isNotBlank()) {
                startMs + parseDuration(durationRaw)
            } else if (allDay) {
                startMs + 86400000L
            } else {
                startMs + 3600000L // 默认 1 小时
            }
        }

        return Event(
            summary = summary.ifEmpty { "未命名日程" },
            startTimeMillis = startMs,
            endTimeMillis = endMs,
            location = location,
            description = description,
            allDay = allDay,
        )
    }

    /**
     * 将日程直接添加到系统日历（通过系统 Intent，免权限）。
     */
    fun addToCalendar(context: Context, event: Event): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, event.summary)
            if (event.startTimeMillis > 0) {
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startTimeMillis)
            }
            if (event.endTimeMillis > 0) {
                intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endTimeMillis)
            }
            if (event.location.isNotBlank()) {
                intent.putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
            }
            if (event.description.isNotBlank()) {
                intent.putExtra(CalendarContract.Events.DESCRIPTION, event.description)
            }
            if (event.allDay) {
                intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun parseDate(raw: String): Pair<Long, Boolean> {
        if (raw.isBlank()) return 0L to false
        val s = raw.trim()

        // 全天事件格式: 20261118 (8位纯数字)
        if (s.matches(Regex("^\\d{8}$"))) {
            return runCatching {
                val df = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                (df.parse(s)?.time ?: 0L) to true
            }.getOrDefault(0L to false)
        }

        // UTC 时间格式: 20261118T143000Z
        if (s.endsWith("Z", ignoreCase = true)) {
            val clean = s.removeSuffix("Z").removeSuffix("z")
            return runCatching {
                val fmt = if (clean.contains("T")) "yyyyMMdd'T'HHmmss" else "yyyyMMdd"
                val df = SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                (df.parse(clean)?.time ?: 0L) to false
            }.getOrDefault(0L to false)
        }

        // 本地时间格式: 20261118T143000
        return runCatching {
            val clean = s.substringAfterLast(":")
            val fmt = if (clean.contains("T")) "yyyyMMdd'T'HHmmss" else "yyyyMMdd"
            val df = SimpleDateFormat(fmt, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            (df.parse(clean)?.time ?: 0L) to false
        }.getOrDefault(0L to false)
    }

    private fun parseDuration(raw: String): Long {
        // 简化解析 ISO 8601 持续时间，例如 PT1H30M 或 P1D
        var ms = 0L
        val hours = Regex("(\\d+)H").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("(\\d+)M").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val days = Regex("P(\\d+)D").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        ms += days * 86400000L
        ms += hours * 3600000L
        ms += minutes * 60000L
        return if (ms > 0) ms else 3600000L
    }

    private fun unescape(s: String): String {
        return s.replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
    }
}
