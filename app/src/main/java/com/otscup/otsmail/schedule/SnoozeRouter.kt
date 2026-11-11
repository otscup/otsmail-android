package com.otscup.otsmail.schedule

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SnoozeOpenEvent(
    val reminderId: String? = null,
    val mailId: String? = null,
    val source: String? = null,
    val openList: Boolean = false,
)

/** 通知点按「打开/稍后提醒」入口：MainActivity 投递，App 消费后跳详情或提醒列表。 */
object SnoozeRouter {
    private val _events = MutableSharedFlow<SnoozeOpenEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun open(reminderId: String?, mailId: String?, source: String?, openList: Boolean) {
        _events.tryEmit(SnoozeOpenEvent(reminderId?.takeIf { it.isNotBlank() }, mailId?.takeIf { it.isNotBlank() }, source, openList))
    }
}
