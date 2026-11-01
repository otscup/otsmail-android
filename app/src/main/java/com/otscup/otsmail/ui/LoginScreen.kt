package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import com.otscup.otsmail.BuildConfig
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    session: Session,
    onLoggedIn: () -> Unit,
) {
    val c = OT.colors
    val t = OT.type
    val scope = rememberCoroutineScope()

    var base by remember { mutableStateOf(session.base.ifEmpty { BuildConfig.BASE_URL }) }
    var email by remember { mutableStateOf(session.email) }
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                Api.base = base.trim()
                session.base = base.trim()
                Api.token = ""
                val token = Api.login(email.trim(), pwd)
                Api.token = token
                session.token = token
                val me = Api.me()
                session.accountId = me.accountId
                session.name = me.name
                session.email = me.email
                onLoggedIn()
            } catch (e: Exception) {
                error = e.message ?: "登录失败"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Box(
            modifier = Modifier
                .size(74.dp)
                .shadow(8.dp, RoundedCornerShape(21.dp))
                .clip(RoundedCornerShape(21.dp))
                .background(c.blue, RoundedCornerShape(21.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(OTSpace.s4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OTS", style = t.title1, color = c.label, fontWeight = FontWeight.Bold)
            Text("Mail", style = t.title1, color = c.blue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text("登录到你的邮箱", style = t.subhead, color = c.label2)
        Spacer(Modifier.height(OTSpace.s6))

        OTGroup {
            OTFieldRow("服务器", base, { base = it }, hint = "https://example.com/api")
            OTDivider(inset = 16)
            OTFieldRow("邮箱地址", email, { email = it }, hint = "you@example.com")
            OTDivider(inset = 16)
            OTFieldRow("密码", pwd, { pwd = it }, hint = "密码", secret = true)
        }

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(OTSpace.s3))
            Text(error, style = t.footnote, color = c.red, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(OTSpace.s5))
        OTPrimaryButton(if (busy) "登录中…" else "登录", enabled = !busy) { submit() }
        Spacer(Modifier.height(OTSpace.s3))
        Text(
            "服务器地址就是网页版在用的那台 Worker 的 /api 地址；换成你自己的域名即可。",
            style = t.caption1,
            color = c.label3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
    }
}
