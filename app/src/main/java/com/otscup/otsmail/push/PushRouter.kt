package com.otscup.otsmail.push

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 通知点击带上来的 emailId 先存这里，等 Compose 路由起来后再消费，
 * 避免 Activity 自己持有路由状态（OtsApp 的 route 是私有 sealed interface）。
 */
object PushRouter {

    var pendingEmailId by mutableStateOf<Long?>(null)
        private set

    fun open(intent: Intent?) {
        val id = intent?.getLongExtra(PushNotifications.EXTRA_EMAIL_ID, 0L) ?: 0L
        if (id > 0L) pendingEmailId = id
    }

    fun consume(): Long? {
        val id = pendingEmailId
        pendingEmailId = null
        return id
    }
}
