package com.otscup.otsmail.push

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast

/**
 * 透明 Activity：Android 10+ 后台进程不允许直接写剪贴板，
 * 通知栏的「复制验证码」必须由一个前台 Activity 完成。
 *
 * 没有任何界面、复制后立即 finish，主题是 Translucent 且关闭窗口动画，
 * 不会留下白屏或闪一下整页；也不进最近任务、不留在返回栈。
 */
class CopyCodeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty().trim()
        if (code.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("验证码", code))
            // Android 13+ 系统会自带剪贴板预览，这里不再重复验证码内容
            Toast.makeText(this, "已复制验证码", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_CODE = "copyCode"
    }
}
