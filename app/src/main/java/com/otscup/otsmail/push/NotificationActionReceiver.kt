package com.otscup.otsmail.push

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.Toast
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.MailCache
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.isAuthExpired
import com.otscup.otsmail.ui.MailSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 通知栏三个快捷操作的落地点：标记已读 / 删除 / 快速回复。
 *
 * BroadcastReceiver 不能在 onReceive 里直接发网络请求，所以用 goAsync() + 协程，
 * 并加 15s 超时兜底：无论成功、失败还是超时都要 finish()，否则系统会判 ANR 直接杀进程。
 * 进程可能是被这条广播冷启动的，Api 的 base/token 需要按本地登录态重新灌一遍。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val emailId = intent.getLongExtra(PushNotifications.EXTRA_EMAIL_ID, 0L)
        val action = intent.action
        if (emailId <= 0L || action == null) return
        val app = context.applicationContext
        val result = goAsync()
        scope.launch {
            try {
                withTimeout(TIMEOUT_MS) {
                    Session(app).apply()
                    when (action) {
                        PushNotifications.ACTION_READ -> markRead(app, emailId)
                        PushNotifications.ACTION_DELETE -> delete(app, emailId)
                        PushNotifications.ACTION_REPLY -> reply(app, intent, emailId)
                        PushNotifications.ACTION_MUTE_SENDER -> muteSender(app, intent)
                    }
                }
            } catch (e: Throwable) {
                // 401 已经由 Api.raw 记进 AuthState，由全局横幅提示，这里不再重复弹错
                if (!e.isAuthExpired()) toast(app, "操作失败")
            } finally {
                result.finish()
            }
        }
    }

    /** 标记已读：走既有的 /email/read，列表靠 MailSync 版本号重拉，不在这里拼本地状态。 */
    private suspend fun markRead(context: Context, emailId: Long) {
        Api.markRead(emailId)
        MailCache.markRead(emailId)
        notifyListChanged()
        PushNotifications.cancel(context, emailId)
    }

    /** 删除：与列表删除语义一致，缓存快照里对应项要一起失效。 */
    private suspend fun delete(context: Context, emailId: Long) {
        Api.trash(listOf(emailId))
        MailCache.removeItem(emailId)
        notifyListChanged()
        PushNotifications.cancel(context, emailId)
    }

    /** 快速回复：纯文本回给原发件人，主题沿用 Re: 规则，空文本不发。 */
    private suspend fun reply(context: Context, intent: Intent, emailId: Long) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PushNotifications.EXTRA_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            toast(context, "回复内容不能为空")
            return
        }
        val to = intent.getStringExtra(PushNotifications.EXTRA_SENDER).orEmpty().trim()
        if (to.isEmpty()) {
            toast(context, "缺少原发件人地址")
            return
        }
        val subject = intent.getStringExtra(PushNotifications.EXTRA_SUBJECT).orEmpty()
        Api.send(
            accountId = Session(context).accountId,
            to = listOf(to),
            subject = reSubject(subject),
            text = text,
            html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>" +
                TextUtils.htmlEncode(text).replace("\n", "<br>") + "</body></html>",
        )
        notifyListChanged()
        PushNotifications.cancel(context, emailId)
    }

    /** 静音发件人：纯本地偏好，不发网络；通知本身保留（已读/删除另有两个按钮）。 */
    private fun muteSender(context: Context, intent: Intent) {
        val sender = intent.getStringExtra(PushNotifications.EXTRA_SENDER).orEmpty().trim()
        if (sender.isEmpty()) {
            toast(context, "缺少发件人地址，无法静音")
            return
        }
        PushSettings.mute(context, sender)
        toast(context, "已静音 $sender")
    }

    private fun reSubject(subject: String): String {
        val s = subject.trim()
        return if (s.lowercase().startsWith("re:")) s else "Re: $s"
    }

    private fun notifyListChanged() {
        Handler(Looper.getMainLooper()).post { MailSync.changed() }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** 广播有执行时限，15s 足够覆盖一次网络请求，超时也一定能 finish()。 */
        const val TIMEOUT_MS = 15_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
