package com.otscup.otsmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.otscup.otsmail.R
import com.otscup.otsmail.data.ServerAddress
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch

/**
 * 首次启动的服务器地址引导页：OTSMail 不内置公共服务器，
 * 用户必须先填入自己部署的地址（校验通过后才进入登录页）。
 */
@Composable
fun ServerSetupScreen(
    session: Session,
    onConfigured: () -> Unit,
    prefill: String = "",
    notice: String = "",
) {
    val t = OT.type
    val c = OT.colors
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf(prefill) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        when (val parsed = ServerAddress.parse(address)) {
            is ServerAddress.Parsed.Fail -> error = parsed.message
            is ServerAddress.Parsed.Ok -> {
                busy = true
                error = ""
                scope.launch {
                    val problem = ServerAddress.verify(parsed.apiBase)
                    if (problem != null) {
                        error = problem
                    } else {
                        focus.clearFocus()
                        session.base = parsed.apiBase
                        session.loginDomain = ""
                        session.apply()
                        onConfigured()
                    }
                    busy = false
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgGrouped)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 32.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(OT.dimens.rLg.dp))
                    .background(c.bgCard)
                    .border(1.dp, c.separatorSoft, RoundedCornerShape(OT.dimens.rLg.dp)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ot_mail),
                    contentDescription = null,
                    tint = c.label,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text("连接服务器", style = t.title1, color = c.label)
            Spacer(Modifier.height(8.dp))
            Text(
                "OTSMail 不内置公共服务器。请填写你自己部署的 OTSMail 服务地址。",
                style = t.subhead,
                color = c.label2,
            )
        }

        if (notice.isNotEmpty()) {
            Text(
                notice,
                style = t.footnote,
                color = c.red,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OT.dimens.rCard.dp))
                    .background(c.bgCard)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OT.dimens.rCard.dp))
                .background(c.bgCard)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp),
        ) {
            Text("服务器地址", style = t.footnote, color = c.label2, modifier = Modifier.padding(bottom = 1.dp))
            Box(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                if (address.isEmpty()) {
                    Text(ServerAddress.HINT, style = t.body, color = c.label3)
                }
                BasicTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        if (error.isNotEmpty()) error = ""
                    },
                    singleLine = true,
                    textStyle = t.body.copy(color = c.label),
                    cursorBrush = SolidColor(loginAccent()),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (error.isNotEmpty()) {
            Text(
                error,
                style = t.footnote,
                color = c.red,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        LoginPrimaryButton(if (busy) "正在检查…" else "继续", enabled = !busy) { submit() }

        Spacer(Modifier.height(14.dp))
        Text(
            "只需要填域名，可省略 https://；只支持 https 地址（http 明文已被禁用）。" +
                "地址会保存在本机，之后可在登录页右上角「服务器」里修改。",
            style = t.footnote,
            color = c.label3,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}
