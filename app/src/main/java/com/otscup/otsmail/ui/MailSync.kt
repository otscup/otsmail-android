package com.otscup.otsmail.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 邮件操作版本号：任何一次真实落库的操作（已读/未读、移动、星标、删除、发送）成功后 +1。
 * 列表页把这个值放进 LaunchedEffect 的 key 里，操作完成即重拉当前列表与计数，
 * 不用退出重进，也不会出现「服务端改了、界面还是旧的」。
 */
object MailSync {
    var rev by mutableIntStateOf(0)
        private set

    fun changed() {
        rev++
    }
}
