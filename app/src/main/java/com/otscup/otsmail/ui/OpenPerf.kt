package com.otscup.otsmail.ui

import android.os.SystemClock
import android.util.Log

/**
 * 开信耗时打点。用 `adb logcat -s OTSOpen` 看：
 *
 *   click id=123                    ← 列表点下去
 *   id=123 header +12ms             ← 标题/发件人可看（缓存或列表占位）
 *   id=123 first_frame +48ms        ← 详情首帧
 *   id=123 web_created +70ms        ← WebView 实例建好
 *   id=123 body_painted +260ms      ← 正文真正画出来
 */
object OpenPerf {
    private const val TAG = "OTSOpen"

    private var id = 0L
    private var start = 0L
    private var seen = mutableSetOf<String>()

    fun click(emailId: Long) {
        id = emailId
        start = SystemClock.uptimeMillis()
        seen = mutableSetOf()
        Log.i(TAG, "click id=$emailId")
    }

    /** 这些阶段会随「先骨架 → 网络回来再加载正文」出现第二次，重复记录才有意义 */
    private val repeatable = setOf("body_paint", "body_visible", "body_measured", "body_loaded")

    fun mark(stage: String) {
        if (start == 0L) return
        if (stage !in repeatable && !seen.add(stage)) return
        Log.i(TAG, "id=$id $stage +${SystemClock.uptimeMillis() - start}ms")
    }
}
