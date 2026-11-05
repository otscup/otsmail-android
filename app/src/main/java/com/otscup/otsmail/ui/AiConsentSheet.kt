package com.otscup.otsmail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.ui.theme.OT

/**
 * AI 隐私说明：第一次使用摘要/智能回复/润色时弹出，确认后本机记住不再提示。
 * 不做「悄悄传全文」——调用前必须过这一道。
 */
@Composable
fun AiConsentSheet(onAllow: () -> Unit, onCancel: () -> Unit) {
    val t = OT.type
    val c = OT.colors
    OTSheet(onDismiss = onCancel) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("使用 AI 前请确认", style = t.title3, color = c.label, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text(
                "AI 摘要、智能回复和润色会把相关邮件正文或你选中的文字发送到服务器配置的 AI 服务处理。" +
                    "确认后本机不再重复提示。",
                style = t.footnote,
                color = c.label2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            OTPrimaryButton("继续使用", onClick = onAllow)
            Spacer(Modifier.height(10.dp))
            Text(
                "取消",
                style = t.body.copy(fontWeight = FontWeight.Medium),
                color = c.blue,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .tapArea { onCancel() }
                    .padding(top = 10.dp),
            )
        }
    }
}
