package com.otscup.otsmail.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * `otsmail://qr` 深链（长按图标「扫一扫」/外部唤起）的待处理标记。
 * 未登录时先留在登录页，登录成功后再继续扫码，不能把用户直接丢到错误页。
 */
object QrScanBus {

    var pending by mutableStateOf(false)
        private set

    fun post() {
        pending = true
    }

    fun consume(): Boolean {
        val p = pending
        pending = false
        return p
    }
}
