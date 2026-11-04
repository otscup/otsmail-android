package com.otscup.otsmail.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 401 的唯一入口（Api.raw 里调用）：全 App 只记一次，界面据此在顶部显示一条提示并进入只读态。
 * 不清 token、不清缓存、不自动跳登录页——页面和列表数据都保持原样，重新登录后回到原页面。
 * 服务端没有 refresh 接口，所以不做自动续期，只保留手动重登。
 */
object AuthState {
    /** >0 表示「登录已过期」提示待展示；0 = 正常。 */
    var expiredAt by mutableStateOf(0L)
        private set

    fun expire() {
        if (expiredAt == 0L) expiredAt = System.currentTimeMillis()
    }

    fun reset() {
        expiredAt = 0L
    }

    fun readOnly(): Boolean = expiredAt > 0L
}

/** 调用方用它判断「这条错误要不要展示」：401 交给全局提示，不要再弹「请求失败」。 */
fun Throwable.isAuthExpired(): Boolean = this is ApiException && httpCode == 401
