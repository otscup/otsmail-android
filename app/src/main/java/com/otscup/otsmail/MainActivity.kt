package com.otscup.otsmail

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.otscup.otsmail.data.OAuthCallbackBus
import com.otscup.otsmail.push.PushDedupe
import com.otscup.otsmail.push.PushNotifications
import com.otscup.otsmail.push.PushRouter
import com.otscup.otsmail.ui.OtsApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushNotifications.ensureChannel(this)
        // 冷启动点通知：把 emailId 交给路由，等 Compose 起来后跳详情
        PushRouter.open(intent)
        // 冷启动由 Custom Tabs 回调拉起：otsmail://oauth/callback?code&state
        handleOAuthIntent(intent)
        enableEdgeToEdge()
        setContent { OtsApp() }
        maybeRunPushDedupeSelfTest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App 在运行中点通知：singleTop 会走到这里
        PushRouter.open(intent)
        handleOAuthIntent(intent)
        maybeRunPushDedupeSelfTest(intent)
    }

    /** 只认 otsmail://oauth/callback，push 深链走 extras，两者互不干扰。 */
    private fun handleOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "otsmail" && data.host == "oauth") {
            OAuthCallbackBus.post(data.toString())
        }
    }

    /**
     * 通知去重自测入口（服务端当前是 data-only，正常收信不会触发去重分支）：
     * adb shell am start -n com.otscup.otsmail/.MainActivity --ez ots_push_selftest true
     */
    private fun maybeRunPushDedupeSelfTest(intent: Intent?) {
        if (intent?.getBooleanExtra("ots_push_selftest", false) != true) return
        val result = PushDedupe.selfTest(this)
        Log.i("PushDedupe", "selfTest -> $result")
        Toast.makeText(this, "通知去重自测：$result", Toast.LENGTH_LONG).show()
    }
}
