package com.otscup.otsmail.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 绑定向导状态机：
 *
 *   ADDRESS ──(识别到服务商 / 转手动)──▶ CREDENTIAL
 *   CREDENTIAL ──(验证并绑定)──▶ 探测 IMAP+SMTP ──失败──▶ CREDENTIAL（带分类错误）
 *                                        └──成功──▶ SAVING ──▶ DONE
 *
 * 只有 DONE 才是真正绑上；Saving 失败会退回 CREDENTIAL，已填的授权码保留在内存里方便重试。
 */
enum class ExtBindStep { ADDRESS, CREDENTIAL, SAVING, DONE }

data class ExtBindState(
    val step: ExtBindStep = ExtBindStep.ADDRESS,
    val address: String = "",
    val provider: ExtProvider? = null,
    val authMode: ExtAuthMode? = null,
    val secret: String = "",
    val manual: Boolean = false,
    val imapHost: String = "",
    val imapPort: String = "993",
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val busy: Boolean = false,
    val probe: ExtProbeResult? = null,
    val error: String = "",
    val hint: String = "",
    val doneText: String = "",
)

class ExtBinder {

    var state by mutableStateOf(ExtBindState())
        private set

    val addressValid: Boolean
        get() = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(state.address.trim())

    fun onAddressChanged(value: String) {
        val provider = ExtProviders.detect(value)
        state = state.copy(
            address = value,
            provider = provider,
            authMode = provider?.authModes?.firstOrNull { it.enabled } ?: provider?.authModes?.firstOrNull(),
            manual = false,
            error = "",
            hint = "",
        )
    }

    fun useManualProvider() {
        state = state.copy(
            provider = ExtProviders.manual,
            authMode = ExtProviders.manual.authModes.first(),
            manual = true,
            error = "",
            hint = "",
        )
    }

    fun onSecretChanged(value: String) {
        state = state.copy(secret = value, error = "", hint = "")
    }

    fun onAuthModeChanged(mode: ExtAuthMode) {
        state = state.copy(authMode = mode, error = "", hint = "")
    }

    fun onServerChanged(imapHost: String, imapPort: String, smtpHost: String, smtpPort: String) {
        state = state.copy(imapHost = imapHost, imapPort = imapPort, smtpHost = smtpHost, smtpPort = smtpPort)
    }

    fun next(): Boolean {
        if (!addressValid) {
            state = state.copy(error = "邮箱地址格式不正确", hint = "")
            return false
        }
        if (state.provider == null) {
            state = state.copy(error = "未识别这个邮箱域名，请改用手动填写服务器", hint = "")
            return false
        }
        state = state.copy(step = ExtBindStep.CREDENTIAL, error = "", hint = "")
        return true
    }

    fun back() {
        state = if (state.step == ExtBindStep.CREDENTIAL) {
            state.copy(step = ExtBindStep.ADDRESS, error = "", hint = "")
        } else {
            state.copy(error = "", hint = "")
        }
    }

    fun reset() {
        state = ExtBindState()
    }

    /** 先探测再绑定；任何一步失败都停在 CREDENTIAL 并给出分类提示。 */
    suspend fun verifyAndBind(): ExtProbeResult? {
        val provider = state.provider ?: run {
            state = state.copy(error = "未识别邮箱服务商", hint = "")
            return null
        }
        val mode = state.authMode ?: run {
            state = state.copy(error = "请选择授权方式", hint = "")
            return null
        }
        if (!mode.enabled) {
            state = state.copy(error = mode.note.ifEmpty { "该授权方式尚未接入" }, hint = "")
            return null
        }
        if (state.secret.isBlank()) {
            state = state.copy(error = "请填写授权码 / 应用专用密码", hint = "")
            return null
        }

        val imapServer = if (state.manual) {
            val port = state.imapPort.toIntOrNull()
            if (state.imapHost.isBlank() || port == null) {
                state = state.copy(error = "请填写 IMAP 主机与端口", hint = "")
                return null
            }
            ExtServer(state.imapHost.trim(), port, secure = port == 993)
        } else provider.imap

        val smtpServer = if (state.manual) {
            val port = state.smtpPort.toIntOrNull()
            if (state.smtpHost.isBlank() || port == null) {
                state = state.copy(error = "请填写 SMTP 主机与端口", hint = "")
                return null
            }
            ExtServer(state.smtpHost.trim(), port, secure = port == 465)
        } else provider.smtp

        state = state.copy(busy = true, error = "", hint = "", probe = null)
        val result = ExtProbe.run(
            provider = provider,
            address = state.address.trim(),
            secret = state.secret.trim(),
            manualImap = imapServer,
            manualSmtp = smtpServer,
        )

        if (!result.ok) {
            state = state.copy(
                busy = false,
                probe = result,
                error = result.detail.ifEmpty { "验证失败" },
                hint = result.hint,
            )
            return result
        }

        state = state.copy(busy = false, probe = result, step = ExtBindStep.SAVING)
        return try {
            val saved = Api.bindExtAccount(
                address = state.address.trim(),
                providerId = provider.id,
                authType = mode.type.wire,
                secret = state.secret.trim(),
                imapOk = result.imapOk,
                smtpOk = result.smtpOk,
                imapIdle = result.imapIdle,
                imapHost = imapServer.host,
                imapPort = imapServer.port,
                smtpHost = smtpServer.host,
                smtpPort = smtpServer.port,
            )
            state = state.copy(
                step = ExtBindStep.DONE,
                doneText = "${state.address.trim()} 已绑定，${saved.optString("provider")} 账号已加入收件箱列表。",
            )
            result
        } catch (e: Throwable) {
            // 保存失败不影响已经通过的探测结果：留在 CREDENTIAL，别让异常把 App 崩掉
            state = state.copy(
                step = ExtBindStep.CREDENTIAL,
                error = friendlyError(e).ifEmpty { e.message ?: "保存到服务端失败" },
                hint = "连通性已通过，但服务端没有保存成功，稍后重试即可，不用重新生成授权码。",
            )
            result
        }
    }
}
