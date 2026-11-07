package com.otscup.otsmail.push

import android.content.Context
import java.util.Calendar

/**
 * 通知偏好：总开关 / 声音 / 震动 / 按发件人静音 / 免打扰时段。
 *
 * 只存开关与时间，不涉及凭据。所有读取都发生在「要发通知」那一刻，
 * 所以改完设置下一条推送就生效，不需要重启 App。
 */
object PushSettings {

    private const val SP = "otsmail_notif"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EXT_ENABLED = "extEnabled"
    private const val KEY_EXT_PUSH_MODE = "extPushMode"
    private const val KEY_SOUND = "sound"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_MUTED = "mutedSenders"
    private const val KEY_DND_ON = "dndOn"
    private const val KEY_DND_START = "dndStart"
    private const val KEY_DND_END = "dndEnd"

    /** 默认免打扰 22:00–07:00，但默认不开启。 */
    const val DEFAULT_DND_START = 22 * 60
    const val DEFAULT_DND_END = 7 * 60

    private fun sp(context: Context) = context.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 外部邮箱（第三方 IMAP）通知开关：与 otsmail 邮件的总开关分开，默认开启。 */
    fun extEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_EXT_ENABLED, true)

    fun setExtEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_EXT_ENABLED, value).apply()
    }

    /**
     * 第三方邮件提醒：外部邮箱设置页只有一个「保持长连接」开关。
     * - 开（[EXT_PUSH_AUTO]）：第三方邮箱走 IMAP IDLE，正常弹第三方邮件通知；
     * - 关（[EXT_PUSH_OFF]）：不连 IDLE、不轮询、不弹第三方通知，手动同步照旧。
     *
     * 默认开，沿用升级前用户的实际行为，不能突然静默。
     * 历史值 `auto`/`idle` 归一到开；历史值 `fcm`（旧的「只用设备推送（调试）」）归一到关——
     * 它本来就收不到提醒，语义上最接近「关闭」，绝不能归一到开以免突然开始耗电。
     */
    const val EXT_PUSH_AUTO = "auto"
    const val EXT_PUSH_OFF = "off"

    /** 读取时归一化历史值，纯函数，便于单测覆盖老数据。 */
    fun normalizeExtPushMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
        EXT_PUSH_OFF, "fcm" -> EXT_PUSH_OFF
        else -> EXT_PUSH_AUTO
    }

    fun extPushMode(context: Context): String =
        normalizeExtPushMode(sp(context).getString(KEY_EXT_PUSH_MODE, EXT_PUSH_AUTO))

    fun setExtPushMode(context: Context, mode: String) {
        sp(context).edit().putString(KEY_EXT_PUSH_MODE, normalizeExtPushMode(mode)).apply()
    }

    /** 开关形态：开 = 保持 IMAP 长连接并提醒第三方新邮件。 */
    fun extPushKeepAlive(context: Context): Boolean = extPushMode(context) != EXT_PUSH_OFF

    fun setExtPushKeepAlive(context: Context, value: Boolean) {
        setExtPushMode(context, if (value) EXT_PUSH_AUTO else EXT_PUSH_OFF)
    }

    fun sound(context: Context): Boolean = sp(context).getBoolean(KEY_SOUND, true)

    fun setSound(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SOUND, value).apply()
    }

    fun vibrate(context: Context): Boolean = sp(context).getBoolean(KEY_VIBRATE, true)

    fun setVibrate(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_VIBRATE, value).apply()
    }

    /** 发件人一律按小写比较，显示时用原始值。 */
    private fun key(sender: String): String = sender.trim().lowercase()

    fun mutedSenders(context: Context): Set<String> =
        sp(context).getStringSet(KEY_MUTED, emptySet()).orEmpty()

    fun isMuted(context: Context, sender: String): Boolean =
        key(sender).isNotEmpty() && mutedSenders(context).contains(key(sender))

    fun mute(context: Context, sender: String) {
        val k = key(sender)
        if (k.isEmpty()) return
        sp(context).edit().putStringSet(KEY_MUTED, mutedSenders(context) + k).apply()
    }

    fun unmute(context: Context, sender: String) {
        val k = key(sender)
        sp(context).edit().putStringSet(KEY_MUTED, mutedSenders(context) - k).apply()
    }

    /**
     * 退出登录/换号：只清与账号绑定的静音名单（它来自上一个账号的邮件与联系人）。
     * 总开关/声音/震动/免打扰是设备级偏好，下个账号继续沿用，不清。
     */
    fun clear(context: Context) {
        sp(context).edit().remove(KEY_MUTED).apply()
    }

    fun dndEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_DND_ON, false)

    fun setDndEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DND_ON, value).apply()
    }

    fun dndStart(context: Context): Int = sp(context).getInt(KEY_DND_START, DEFAULT_DND_START)

    fun dndEnd(context: Context): Int = sp(context).getInt(KEY_DND_END, DEFAULT_DND_END)

    fun setDndStart(context: Context, minutes: Int) {
        sp(context).edit().putInt(KEY_DND_START, minutes.coerceIn(0, 24 * 60 - 1)).apply()
    }

    fun setDndEnd(context: Context, minutes: Int) {
        sp(context).edit().putInt(KEY_DND_END, minutes.coerceIn(0, 24 * 60 - 1)).apply()
    }

    /**
     * 免打扰判定用设备本地时区；22:00–07:00 这种跨午夜区间按「>= 开始 或 < 结束」。
     * 必须先看开关：设置页里「开启免打扰」是关闭状态时，默认时间段不能偷偷生效
     * （否则夜里所有通知都会被拦，而界面上还显示「未开启」）。
     */
    fun inDnd(context: Context): Boolean =
        dndEnabled(context) && inDnd(dndStart(context), dndEnd(context), nowMinutes())

    /** 起止相同视为未设置（不是 24 小时免打扰）。 */
    fun inDnd(start: Int, end: Int, now: Int): Boolean {
        if (!dndRangeValid(start, end, now)) return false
        return if (start == end) {
            false
        } else if (start < end) {
            now in start until end
        } else {
            now >= start || now < end
        }
    }

    private fun dndRangeValid(start: Int, end: Int, now: Int): Boolean =
        start in 0 until 24 * 60 && end in 0 until 24 * 60 && now in 0 until 24 * 60

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 按当前声音 × 震动组合选渠道。渠道属性建成后 App 改不动，所以必须提前建好四条。 */
    fun channelId(context: Context): String {
        val sound = sound(context)
        val vibrate = vibrate(context)
        return when {
            sound && vibrate -> PushNotifications.CHANNEL_SOUND_VIBRATE
            sound -> PushNotifications.CHANNEL_SOUND
            vibrate -> PushNotifications.CHANNEL_VIBRATE
            else -> PushNotifications.CHANNEL_SILENT
        }
    }

    fun formatTime(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}
