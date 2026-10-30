package com.otscup.otsmail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 草稿只存在本机（SharedPreferences）。
 * 网页版的草稿在浏览器的 IndexedDB 里，后端没有表；要跨端同步得加 draft 表。
 */
object Drafts {
    private const val KEY = "otsmail.drafts"
    private const val MAX = 20

    private fun sp(ctx: Context) = ctx.getSharedPreferences("otsmail_draft", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<JSONObject> {
        val raw = sp(ctx).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun save(ctx: Context, to: String, subject: String, text: String) {
        if (to.isBlank() && subject.isBlank() && text.isBlank()) return
        val all = list(ctx).toMutableList()
        val stamp = System.currentTimeMillis()
        all.removeAll { it.optString("to") == to && it.optString("subject") == subject }
        all.add(0, JSONObject().put("to", to).put("subject", subject).put("text", text).put("at", stamp))
        val out = JSONArray()
        all.take(MAX).forEach { out.put(it) }
        sp(ctx).edit().putString(KEY, out.toString()).apply()
    }

    fun remove(ctx: Context, at: Long) {
        val out = JSONArray()
        list(ctx).filter { it.optLong("at") != at }.forEach { out.put(it) }
        sp(ctx).edit().putString(KEY, out.toString()).apply()
    }
}
