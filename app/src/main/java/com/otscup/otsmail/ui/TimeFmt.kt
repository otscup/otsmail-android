package com.otscup.otsmail.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * 全 App 唯一的时间格式化入口（列表行 / 分组头 / 详情页 / 全部邮件都用这里，不再各写一份）。
 *
 * 服务端 `createTime` 是 `yyyy-MM-dd HH:mm:ss` 且**不带时区**，其值为 UTC
 * （D1/SQLite 的 CURRENT_TIMESTAMP 就是 UTC；服务端统计时也要 `+8 hours` 才是本地日）。
 * 所以：无时区字符串先按 UTC 解析 → 再转设备本地时区展示；数字则按 10 位秒 / 13 位毫秒处理。
 * 不依赖 SimpleDateFormat 的隐式默认时区，Locale 固定中文，避免设备语言变化出现英文月份。
 */
internal object TimeFmt {

    /** 设备本地时区；取不到时兜底 Asia/Shanghai。 */
    private val zone: ZoneId = runCatching { ZoneId.systemDefault() }
        .getOrDefault(ZoneId.of("Asia/Shanghai"))

    private val CN: Locale = Locale.SIMPLIFIED_CHINESE

    /** 服务端无时区格式；秒与毫秒（`.123`）可缺省。 */
    private val serverFmt: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .toFormatter(CN)

    /** 原始值 → 时间点（UTC 语义，随后统一转本地）。解析不出来返回 null，调用方原样兜底。 */
    private fun toInstant(raw: String): Instant? {
        val txt = raw.trim()
        if (txt.isEmpty()) return null

        // epoch：10 位秒 / 13 位毫秒
        if (txt.all { it.isDigit() }) {
            val n = txt.toLongOrNull() ?: return null
            return if (n > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
        }

        // ISO8601：带 Z 或带偏移（属于明确的绝对时刻，直接采用）
        runCatching { return Instant.parse(txt) }
        runCatching { return OffsetDateTime.parse(txt).toInstant() }

        // 服务端 `yyyy-MM-dd HH:mm:ss`：没有时区标记 → 按 UTC 解释
        runCatching {
            return LocalDateTime.parse(txt, serverFmt).toInstant(ZoneOffset.UTC)
        }
        // 只有日期 `yyyy-MM-dd` → 当天 UTC 零点
        runCatching {
            return LocalDate.parse(txt).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return null
    }

    /** 原始时间 → epoch 毫秒（合并排序用；解析不出来返回 null，由调用方兜底）。 */
    internal fun epochMs(raw: String): Long? = toInstant(raw)?.toEpochMilli()

    private fun localOf(raw: String): LocalDateTime? =
        toInstant(raw)?.atZone(zone)?.toLocalDateTime()

    /** mm:ss 这类两位补零交给固定中文 Locale。 */
    private fun hm(hour: Int, minute: Int): String = String.format(CN, "%02d:%02d", hour, minute)

    /**
     * 列表行右侧：当天 `HH:mm`，昨天「昨天 HH:mm」，今年更早 `M月d日`，跨年 `yyyy年M月d日`。
     * 日界按设备本地时区判断（北京时间 00:00–08:00 收到的 UTC 前一天邮件不会被算成"更早"）。
     */
    internal fun rowTime(raw: String): String {
        val ldt = localOf(raw) ?: return raw
        val day = ldt.toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            day == today -> hm(ldt.hour, ldt.minute)
            day == today.minusDays(1) -> "昨天 ${hm(ldt.hour, ldt.minute)}"
            day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
            else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        }
    }

    /**
     * 分组键：按**本地时区**的日界算出 `yyyy-MM-dd`。
     * 列表分组与滚动位置回算都必须用同一个键，否则跨 0 点会分裂成两组。
     * 解析失败时退回原始字符串前 10 位，保持不变。
     */
    internal fun dayKey(raw: String): String {
        val ldt = localOf(raw) ?: return raw.take(10)
        return ldt.toLocalDate().toString()
    }

    /** 分组头文案：今天 / 昨天 / `M月d日` / 跨年 `yyyy年M月d日`，同样按本地日界。 */
    internal fun dayLabel(key: String): String {
        val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: return key
        val today = LocalDate.now(zone)
        return when {
            day == today -> "今天"
            day == today.minusDays(1) -> "昨天"
            day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
            else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        }
    }

    /** 详情页：完整本地时间 `2026年11月4日 09:12`。 */
    internal fun detailTime(raw: String): String {
        val ldt = localOf(raw) ?: return raw
        return "${ldt.year}年${ldt.monthValue}月${ldt.dayOfMonth}日 ${hm(ldt.hour, ldt.minute)}"
    }

    /**
     * 只需日期的场景（用户注册时间、注册码到期日）：按设备本地时区取 `yyyy-MM-dd`。
     * 服务端是 UTC 时间戳，直接 `take(10)` 会让北京时间 0–8 点产生的记录显示成前一天。
     * 解析不出来时退回原始字符串前 10 位，保持旧行为。
     */
    internal fun localDate(raw: String): String {
        val ldt = localOf(raw) ?: return raw.take(10)
        return ldt.toLocalDate().toString()
    }

    /**
     * 设备/会话、通行密钥这类后台记录：完整本地时间 `2026-11-11 21:10:38`。
     * 不带 T/Z/毫秒，固定空格分隔；服务端仍给 ISO 8601 UTC，这里只负责换算到设备本地时区。
     * 秒缺失时补 00；解析不出来原样返回，避免把时间整段吞掉。
     */
    internal fun preciseTime(raw: String): String {
        val ldt = localOf(raw) ?: return raw
        return String.format(
            CN,
            "%04d-%02d-%02d %02d:%02d:%02d",
            ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute, ldt.second,
        )
    }
}
