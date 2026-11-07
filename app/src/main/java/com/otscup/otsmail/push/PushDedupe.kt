package com.otscup.otsmail.push

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知去重。当前服务端发的 FCM 是 data-only（payload 里没有 `notification` 块），
 * 所以这条规则处于「休眠」状态：data-only 一律照常建通知，行为和以前一样。
 *
 * 一旦服务端以后带上 `notification` 块（系统自己会展示一条），同一条 FCM 到达 onMessageReceived
 * 时我们若再造一条就会重复。这里记录最近展示过的 emailId（内存 + 持久化，最近 200 条 / 10 分钟窗口），
 * payload 带 notification 且同 emailId 已展示过 → 不再建通知。
 *
 * 自测入口：`adb shell am start -n com.otscup.otsmail/.MainActivity --ez ots_push_selftest true`
 * 结果同时打到 logcat（tag=PushDedupe）和 Toast。
 */
object PushDedupe {
    private const val SP = "otsmail_push_dedupe"
    private const val KEY = "seen"
    private const val KEY_EXT = "seenExt"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX = 200
    private const val TAG = "PushDedupe"

    private val mem = LinkedHashMap<Long, Long>() // emailId -> 首次展示时间
    private val memExt = LinkedHashMap<String, Long>() // "extId|messageId" -> 首次展示时间

    /** @return true = 应该建通知；false = 同 emailId 已展示过且这次带 notification 块，跳过。 */
    @Synchronized
    fun decide(ctx: Context, emailId: Long, hasNotificationBlock: Boolean): Boolean {
        if (emailId <= 0) return true
        val now = System.currentTimeMillis()
        val seen = LinkedHashMap<Long, Long>()
        mem.forEach { (k, v) -> if (now - v <= WINDOW_MS) seen[k] = v }
        read(ctx).forEach { (k, v) -> if (now - v <= WINDOW_MS) seen[k] = v }
        val dup = seen.containsKey(emailId)
        seen[emailId] = now
        // 只留最近 200 条
        val pruned = LinkedHashMap<Long, Long>()
        seen.entries.sortedBy { it.value }.takeLast(MAX).forEach { pruned[it.key] = it.value }
        mem.clear()
        mem.putAll(pruned)
        write(ctx, pruned)
        val show = !(hasNotificationBlock && dup)
        Log.i(
            TAG,
            "emailId=$emailId hasNotification=$hasNotificationBlock dup=$dup show=$show window=${pruned.size}",
        )
        return show
    }

    /**
     * 外部邮箱去重：按 (extId, messageId) + 时间窗。
     *
     * 同一条邮件可能被两个通道各触发一次（IMAP IDLE 与服务端 FCM 触发），
     * 也可能因断线重连、重复唤醒在 10 分钟内再次走到通知逻辑；
     * 返回 true = 这次应该弹通知，false = 时间窗内已经弹过。
     */
    @Synchronized
    fun decideExt(ctx: Context, extId: Long, messageId: String, windowMs: Long = WINDOW_MS): Boolean {
        val id = messageId.trim()
        if (extId <= 0L || id.isEmpty()) return true
        val key = "$extId|$id"
        val now = System.currentTimeMillis()
        val seen = LinkedHashMap<String, Long>()
        memExt.forEach { (k, v) -> if (now - v <= windowMs) seen[k] = v }
        readExt(ctx).forEach { (k, v) -> if (now - v <= windowMs) seen[k] = v }
        val dup = seen.containsKey(key)
        if (!dup) seen[key] = now
        val pruned = LinkedHashMap<String, Long>()
        seen.entries.sortedBy { it.value }.takeLast(MAX).forEach { pruned[it.key] = it.value }
        memExt.clear()
        memExt.putAll(pruned)
        writeExt(ctx, pruned)
        Log.i(TAG, "extDedupe ext=$extId message=$id dup=$dup show=${!dup} window=${pruned.size}")
        return !dup
    }

    /** 自测：只打印结果，不改真实通知逻辑。 */
    fun selfTest(ctx: Context): String {
        // 用 10 位的时间戳做测试 id：正值（decide 只对 emailId>0 生效）且不会撞到真实 emailId
        val id = 8_000_000_000L + (System.currentTimeMillis() % 1_000_000_000L)
        val firstNotification = decide(ctx, id, true)
        val secondNotification = decide(ctx, id, true)
        val firstDataOnly = decide(ctx, id + 1, false)
        val secondDataOnly = decide(ctx, id + 1, false)
        val result = "notification=[$firstNotification,$secondNotification]（期望 true,false） " +
            "dataOnly=[$firstDataOnly,$secondDataOnly]（期望 true,true）"
        Log.i(TAG, "selfTest -> $result")
        return result
    }

    /** 退出登录/换号：去重表属于上一个账号的通知历史，不能带到下一个账号。 */
    @Synchronized
    fun clear(ctx: Context) {
        mem.clear()
        memExt.clear()
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().remove(KEY).remove(KEY_EXT).apply()
    }

    private fun read(ctx: Context): Map<Long, Long> {
        val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Long, Long>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out[o.optLong("id")] = o.optLong("at")
        }
        return out
    }

    private fun write(ctx: Context, map: Map<Long, Long>) {
        val arr = JSONArray()
        map.forEach { (id, at) -> arr.put(JSONObject().put("id", id).put("at", at)) }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun readExt(ctx: Context): Map<String, Long> {
        val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_EXT, null) ?: return emptyMap()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            if (k.isNotEmpty()) out[k] = o.optLong("at")
        }
        return out
    }

    private fun writeExt(ctx: Context, map: Map<String, Long>) {
        val arr = JSONArray()
        map.forEach { (k, at) -> arr.put(JSONObject().put("k", k).put("at", at)) }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_EXT, arr.toString()).apply()
    }
}
