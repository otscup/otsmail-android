package com.otscup.otsmail.data

/**
 * 第三方邮箱自动探测规则表（离线副本）。
 * 服务端 mail-worker/src/utils/mail-providers.js 是同一份数据的真源，改动必须两边同步。
 *
 * 置信度：
 * - Gmail / Outlook / 163 / 126 / QQ 的主机端口来自各家官方帮助页。
 * - foxmail.com 复用 QQ 服务器（推断）。
 * - 未命中的域名一律走「其他邮箱」手动填写，不猜。
 */
enum class ExtAuthType(val wire: String) {
    APP_PASSWORD("app_password"),
    AUTH_CODE("auth_code"),
    OAUTH("oauth"),
}

data class ExtServer(
    val host: String,
    val port: Int,
    val secure: Boolean,
    val needsId: Boolean = false,
)

data class ExtAuthMode(
    val type: ExtAuthType,
    val label: String,
    val steps: List<String>,
    val enabled: Boolean = true,
    val note: String = "",
)

data class ExtProvider(
    val id: String,
    val name: String,
    val domains: List<String>,
    val imap: ExtServer,
    val smtp: ExtServer,
    val authModes: List<ExtAuthMode>,
    val note: String,
    val manual: Boolean = false,
)

object ExtProviders {

    val manual = ExtProvider(
        id = "custom",
        name = "其他邮箱（手动填写）",
        domains = emptyList(),
        imap = ExtServer("", 993, true),
        smtp = ExtServer("", 465, true),
        authModes = listOf(
            ExtAuthMode(ExtAuthType.AUTH_CODE, "授权码 / 密码", emptyList()),
            ExtAuthMode(ExtAuthType.APP_PASSWORD, "应用专用密码", emptyList()),
        ),
        note = "未收录的域名需要自己填写 IMAP / SMTP 主机与端口。",
        manual = true,
    )

    val all: List<ExtProvider> = listOf(
        ExtProvider(
            id = "gmail",
            name = "Gmail",
            domains = listOf("gmail.com", "googlemail.com"),
            imap = ExtServer("imap.gmail.com", 993, true),
            smtp = ExtServer("smtp.gmail.com", 465, true),
            authModes = listOf(
                ExtAuthMode(
                    ExtAuthType.APP_PASSWORD, "应用专用密码",
                    listOf(
                        "浏览器打开 myaccount.google.com/security",
                        "开启「两步验证」（应用专用密码必须先开两步验证）",
                        "搜索「应用专用密码」，新建一个，选择「邮件」",
                        "把生成的 16 位密码粘贴到这里（不是 Google 账号密码）",
                    ),
                ),
                ExtAuthMode(
                    ExtAuthType.OAUTH, "Google 账号授权登录", emptyList(), enabled = false,
                    note = "OAuth 授权在下一批接入；本批请用应用专用密码。",
                ),
            ),
            note = "Gmail 只接受「应用专用密码」：先开两步验证再生成 16 位密码，不能直接填 Google 账号密码；" +
                "OAuth 尚未接入。另外需要能访问 Google 服务器的网络，否则会停在 TLS/连接阶段（不是授权码错误）。" +
                "IMAP 需在网页版「转发和 POP/IMAP」里保持启用。",
        ),
        ExtProvider(
            id = "outlook",
            name = "Outlook / Hotmail",
            domains = listOf("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.jp"),
            imap = ExtServer("outlook.office365.com", 993, true),
            smtp = ExtServer("smtp.office365.com", 587, false),
            authModes = listOf(
                ExtAuthMode(
                    ExtAuthType.OAUTH, "Microsoft 账号授权登录", emptyList(), enabled = false,
                    note = "Microsoft 已于 2024-09-16 停用基础认证，Outlook 只能用 OAuth；本批为占位。",
                ),
            ),
            note = "Outlook 不支持应用密码走 IMAP/SMTP，必须 OAuth（下一批接入）。",
        ),
        ExtProvider(
            id = "netease163",
            name = "网易 163",
            domains = listOf("163.com", "vip.163.com", "188.com"),
            imap = ExtServer("imap.163.com", 993, true, needsId = true),
            smtp = ExtServer("smtp.163.com", 465, true),
            authModes = listOf(
                ExtAuthMode(
                    ExtAuthType.AUTH_CODE, "客户端授权码",
                    listOf(
                        "网页登录 mail.163.com",
                        "右上角「设置」→「POP3/SMTP/IMAP」",
                        "开启「IMAP/SMTP 服务」",
                        "同页「客户端授权码」里新增一个，复制 16 位授权码粘贴到这里",
                    ),
                ),
            ),
            note = "163 系 IMAP 必须先发送 ID 命令声明客户端名，否则服务器回 Unsafe Login。",
        ),
        ExtProvider(
            id = "netease126",
            name = "网易 126",
            domains = listOf("126.com", "yeah.net"),
            imap = ExtServer("imap.126.com", 993, true, needsId = true),
            smtp = ExtServer("smtp.126.com", 465, true),
            authModes = listOf(
                ExtAuthMode(
                    ExtAuthType.AUTH_CODE, "客户端授权码",
                    listOf(
                        "网页登录 mail.126.com",
                        "右上角「设置」→「POP3/SMTP/IMAP」",
                        "开启「IMAP/SMTP 服务」",
                        "在「客户端授权码」里新增并复制授权码粘贴到这里",
                    ),
                ),
            ),
            note = "yeah.net 与 126 同属网易免费邮，先按 126 主机尝试；失败可改手动填写。",
        ),
        ExtProvider(
            id = "qq",
            name = "QQ 邮箱",
            domains = listOf("qq.com", "foxmail.com"),
            imap = ExtServer("imap.qq.com", 993, true),
            smtp = ExtServer("smtp.qq.com", 465, true),
            authModes = listOf(
                ExtAuthMode(
                    ExtAuthType.AUTH_CODE, "16 位授权码",
                    listOf(
                        "网页登录 mail.qq.com",
                        "「设置」→「账号」→ 开启「IMAP/SMTP 服务」",
                        "按提示用手机发送短信完成验证",
                        "复制弹出的 16 位授权码粘贴到这里（不是 QQ 密码）",
                    ),
                ),
            ),
            note = "foxmail.com 域名复用 QQ 的服务器（推断，未逐条实测）。",
        ),
    )

    private fun domainOf(address: String): String {
        val at = address.lastIndexOf('@')
        return if (at == -1) "" else address.substring(at + 1).trim().lowercase()
    }

    fun detect(address: String): ExtProvider? {
        val domain = domainOf(address.trim())
        if (domain.isEmpty()) return null
        return all.firstOrNull { p ->
            p.domains.any { d -> domain == d || domain.endsWith(".$d") }
        }
    }

    fun byId(id: String): ExtProvider? =
        if (id == manual.id) manual else all.firstOrNull { it.id == id }
}
