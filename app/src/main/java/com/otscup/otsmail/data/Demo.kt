package com.otscup.otsmail.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.otscup.otsmail.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 仅演示构建启用的本地假数据（`./gradlew assembleDebug -Pdemo=true`）。
 *
 * 演示包 applicationId 带 `.demo` 后缀，和正式包并存；[BuildConfig.DEMO]=false 时
 * [Api] 根本不会进这里，正式包不读也不写这份数据。
 *
 * 所有账户、发件人、正文均为虚构；时间按「现在往前推」生成（UTC，与真实服务端
 * createTime 的时区约定一致），所以截图上永远显示今天/昨天，不会过期。
 */
object Demo {

    const val TOKEN = "demo-token"

    /** 正式包恒为 false（BuildConfig.DEMO 由 -Pdemo=true 决定）。 */
    val enabled: Boolean get() = BuildConfig.DEMO

    /**
     * 演示账户的人像：只存在于演示构建的 `src/demo/res/drawable-nodpi/demo_portrait.jpg`
     * （正式构建不把 `src/demo/res` 加进 res.srcDirs，所以正式包里根本没有这张图）。
     * 用 getIdentifier 取而不是 R.drawable.xxx：正式包链路上拿不到 id 就返回空串。
     */
    @Volatile
    private var portraitDataUrl: String? = null

    fun avatarDataUrl(context: Context): String {
        portraitDataUrl?.let { return it }
        val id = context.resources.getIdentifier("demo_portrait", "drawable", context.packageName)
        if (id == 0) return ""
        val bmp = BitmapFactory.decodeResource(context.resources, id) ?: return ""
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        portraitDataUrl = url
        return url
    }

    data class DemoAccount(val id: Long, val email: String, val name: String)

    val accounts = listOf(
        DemoAccount(1, "demo.lin@example.com", "Demo Lin"),
        DemoAccount(2, "hello@example.org", "Hello Studio"),
        DemoAccount(3, "team@demo-mail.dev", "Demo Team"),
        DemoAccount(4, "notes@sample-inbox.io", "Sample Notes"),
    )

    private data class Mail(
        val id: Long,
        val accountId: Long,
        val from: String,
        val name: String,
        val subject: String,
        val preview: String,
        val html: String,
        val minutesAgo: Int,
        val unread: Boolean,
        val starred: Boolean = false,
        val category: String = "primary",
        val mailbox: String = "receive",
        val attachments: List<Pair<String, String>> = emptyList(),
        /** 原 To / Cc：演示「回复全部」时能带出地址 */
        val recipient: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
    )

    private val mails = mutableListOf(
        Mail(
            id = 9001, accountId = 1,
            from = "no-reply@northwind-id.example", name = "Northwind Identity",
            subject = "Your sign-in code is 481-702",
            preview = "Use this code to finish signing in. It expires in 10 minutes.",
            html = """
                <p>Hi there,</p>
                <p>Use the code below to finish signing in to your Northwind account:</p>
                <p style="font-size:28px;font-weight:600;letter-spacing:2px">481-702</p>
                <p>The code expires in 10 minutes. If you didn't try to sign in, you can ignore this message.</p>
                <p>— Northwind Identity</p>
            """.trimIndent(),
            minutesAgo = 18, unread = true,
        ),
        Mail(
            id = 9002, accountId = 1,
            from = "billing@lumencloud.example", name = "Lumen Cloud Billing",
            subject = "Invoice #2026-1187 has been paid",
            preview = "Your November invoice for Lumen Cloud is settled. Receipt attached.",
            html = """
                <p>Hello Demo Lin,</p>
                <p>Thanks — we received your payment of <b>$24.00</b> for the November billing period.</p>
                <p>Workspace: <b>demo-team</b><br>Invoice: <b>#2026-1187</b><br>Payment method: Visa ending 4242</p>
                <p>You can download the full receipt from the attachment below.</p>
                <p>— Lumen Cloud Billing</p>
            """.trimIndent(),
            minutesAgo = 96, unread = true, category = "updates",
            attachments = listOf("invoice-2026-1187.pdf" to "demo/invoice-2026-1187.pdf"),
        ),
        Mail(
            id = 9003, accountId = 2,
            from = "digest@theweeklyledger.example", name = "The Weekly Ledger",
            subject = "Five small habits that make mornings calmer",
            preview = "This week: slow starts, a shorter inbox, and one good question.",
            html = """
                <p>Good morning,</p>
                <p>This week's issue is about small changes that compound:</p>
                <ul>
                  <li>Leave the first ten minutes of the day unscheduled.</li>
                  <li>Write tomorrow's one priority before you close the laptop.</li>
                  <li>Keep a short list of questions you actually want answered.</li>
                </ul>
                <p>Read the full piece on our site, or reply with what worked for you.</p>
                <p>— The Weekly Ledger</p>
            """.trimIndent(),
            minutesAgo = 150, unread = true, category = "promotion",
        ),
        Mail(
            id = 9004, accountId = 1,
            from = "priya.raman@example.net", name = "Priya Raman",
            subject = "Re: Sunday dinner",
            preview = "Sunday works for us. I'll bring the sourdough if you make the soup.",
            html = """
                <p>Hi!</p>
                <p>Sunday works for us — we can be there around six. I'll bring the sourdough
                if you're still planning to make the soup.</p>
                <p>Also, Ada says she wants to show you her new drawing, so be prepared.</p>
                <p>See you then,<br>Priya</p>
            """.trimIndent(),
            minutesAgo = 230, unread = false, starred = true,
        ),
        Mail(
            id = 9005, accountId = 3,
            from = "avery.cole@demo-mail.dev", name = "Avery Cole",
            subject = "Design review moved to Thursday 10:00",
            preview = "The review is now Thursday at 10:00 in the small room. Agenda attached.",
            html = """
                <p>Team,</p>
                <p>Quick change: the design review moves to <b>Thursday 10:00–11:00</b> in the small room.</p>
                <p>Agenda:</p>
                <ol>
                  <li>Onboarding flow — open questions</li>
                  <li>Calendar sync — prototype walkthrough</li>
                  <li>Next sprint scope</li>
                </ol>
                <p>Please add anything you want covered by Wednesday noon.</p>
                <p>— Avery</p>
            """.trimIndent(),
            minutesAgo = 310, unread = true, category = "updates",
            attachments = listOf("design-review-agenda.pdf" to "demo/design-review-agenda.pdf"),
        ),
        Mail(
            id = 9006, accountId = 4,
            from = "tracking@parcelway.example", name = "Parcelway",
            subject = "Your parcel arrives tomorrow between 09:00 and 12:00",
            preview = "Parcel PW-5591-204 is out for delivery tomorrow morning.",
            html = """
                <p>Your parcel is on its way.</p>
                <p>Tracking number: <b>PW-5591-204</b><br>
                Estimated delivery: tomorrow, 09:00–12:00<br>
                Delivery address: 18 Willow Lane (front door)</p>
                <p>You can change the delivery window in the Parcelway app until 20:00 today.</p>
            """.trimIndent(),
            minutesAgo = 420, unread = false, category = "updates",
        ),
        Mail(
            id = 9007, accountId = 2,
            from = "events@meetupcircle.example", name = "Meetup Circle",
            subject = "3 people you might want to meet this week",
            preview = "A ceramics workshop, a running club, and a reading group near you.",
            html = """
                <p>Hi Hello Studio,</p>
                <p>Three gatherings near you this week:</p>
                <p><b>Ceramics workshop</b> — Thursday 19:00, five spots left.<br>
                <b>Riverside running club</b> — Saturday 08:00, easy pace.<br>
                <b>Reading group</b> — Sunday 15:00, this month's pick is <i>Small Things Like These</i>.</p>
                <p>See details on Meetup Circle.</p>
            """.trimIndent(),
            minutesAgo = 540, unread = false, category = "social",
        ),
        Mail(
            id = 9008, accountId = 3,
            from = "news@riverstonebooks.example", name = "Riverstone Books",
            subject = "New arrivals: fiction, essays, and a cookbook",
            preview = "Fresh on the shelf this week, plus 15% off orders over $40.",
            html = """
                <p>New this week at Riverstone:</p>
                <p>• <b>The Quiet Hours</b> — a novel about a night-shift librarian<br>
                • <b>Notes on Walking</b> — essays from the coast path<br>
                • <b>The Weekday Cookbook</b> — 60 dinners that take under 30 minutes</p>
                <p>This weekend only: 15% off orders over $40 in store and online.</p>
            """.trimIndent(),
            minutesAgo = 720, unread = false, category = "promotion",
        ),
        Mail(
            id = 9009, accountId = 4,
            from = "security@northwind-id.example", name = "Northwind Security",
            subject = "New sign-in from a new device",
            preview = "We noticed a sign-in from Chrome on Windows in Shanghai.",
            html = """
                <p>We noticed a new sign-in to your account:</p>
                <p><b>Device:</b> Chrome on Windows<br>
                <b>Location:</b> Shanghai, CN<br>
                <b>Time:</b> today, 08:41</p>
                <p>If this was you, no action is needed. If not, change your password and review active sessions.</p>
            """.trimIndent(),
            minutesAgo = 860, unread = true, category = "updates",
        ),
        Mail(
            id = 9010, accountId = 1,
            from = "trips@skylineair.example", name = "Skyline Air",
            subject = "Your itinerary for SHA → SZX on 12 November",
            preview = "Flight SL 208, seat 17A. Check-in opens 24 hours before departure.",
            html = """
                <p>Here is your itinerary.</p>
                <p><b>Flight SL 208</b> — Shanghai (SHA) to Shenzhen (SZX)<br>
                12 November, 09:25–11:50<br>Seat 17A · Economy</p>
                <p>Check-in opens 24 hours before departure. Baggage allowance: one 20kg checked bag.</p>
            """.trimIndent(),
            minutesAgo = 1020, unread = false, category = "updates",
        ),
        Mail(
            id = 9011, accountId = 3,
            from = "notifications@forgecode.example", name = "Forge Code",
            subject = "[demo-team/notes] 2 pull requests need review",
            preview = "openapi: add pagination cursor · web: cache account list",
            html = """
                <p>Two pull requests are waiting for your review in <b>demo-team/notes</b>:</p>
                <p>#482 — <b>openapi: add pagination cursor</b> (3 files, +112 −18)<br>
                #483 — <b>web: cache account list</b> (2 files, +64 −9)</p>
                <p>Both have passing checks and no merge conflicts.</p>
            """.trimIndent(),
            minutesAgo = 1260, unread = true, category = "updates",
        ),
        Mail(
            id = 9012, accountId = 2,
            from = "tomas.lindqvist@example.se", name = "Tomas Lindqvist",
            subject = "Photos from the coast hike",
            preview = "A few shots from Saturday — the fog lifted right at noon.",
            html = """
                <p>Hey,</p>
                <p>A few photos from Saturday's hike. The fog lifted right at noon, so the last
                three are the ones worth keeping.</p>
                <p>Next time we should start earlier and bring more water.</p>
                <p>/Tomas</p>
            """.trimIndent(),
            minutesAgo = 1560, unread = false, starred = true,
        ),
        Mail(
            id = 9013, accountId = 1,
            from = "billing@northwind-id.example", name = "Northwind Billing",
            subject = "Invoice #4821 was declined",
            preview = "Your card ending in 4421 was declined for invoice #4821.",
            html = """
                <p>Your card ending in <b>4421</b> was declined for invoice #4821 ($24.00).</p>
                <p>Please update the payment method in your account settings to avoid service interruption.</p>
            """.trimIndent(),
            minutesAgo = 2100, unread = false, category = "updates", mailbox = "deleted",
            recipient = listOf("demo.lin@example.com"),
            cc = listOf("finance@example.org"),
        ),
        Mail(
            id = 9014, accountId = 2,
            from = "newsletter@example.com", name = "Weekly Digest",
            subject = "5 things we learned this week",
            preview = "A short list of links and notes from the team.",
            html = """
                <p>Five quick reads from the team this week:</p>
                <p>1. Notes on caching<br>2. A better onboarding<br>3. Release notes<br>4. Reader mail<br>5. What's next</p>
            """.trimIndent(),
            minutesAgo = 2400, unread = true, mailbox = "deleted",
        ),
        Mail(
            id = 9015, accountId = 1,
            from = "winner@lucky-draw.example", name = "Lucky Draw",
            subject = "You have won a $500 gift card",
            preview = "Claim your prize within 24 hours.",
            html = "<p>Congratulations! Click the link below to claim your $500 gift card.</p>",
            minutesAgo = 2600, unread = true, category = "spam", mailbox = "spam",
        ),
    )

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun timeOf(m: Mail): String =
        LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m.minutesAgo.toLong()).format(fmt)

    private fun sorted(): List<Mail> = mails.sortedBy { it.minutesAgo }

    private fun mailJson(m: Mail): JSONObject = JSONObject()
        .put("emailId", m.id)
        .put("sendEmail", m.from)
        .put("name", m.name)
        .put("subject", m.subject)
        .put("text", m.preview)
        .put("createTime", timeOf(m))
        .put("unread", if (m.unread) 1 else 0)
        .put("isStar", if (m.starred) 1 else 0)
        .put("category", m.category)
        .put("accountId", m.accountId)
        .put("type", 0)
        .put("status", 0)
        .put("recipient", JSONArray(m.recipient))
        .put("cc", JSONArray(m.cc))

    private fun detailJson(id: Long): JSONObject {
        val m = mails.firstOrNull { it.id == id } ?: return JSONObject()
        return mailJson(m)
            .put("content", m.html)
            .put(
                "attList",
                JSONArray().apply {
                    m.attachments.forEach {
                        put(JSONObject().put("filename", it.first).put("key", it.second))
                    }
                },
            )
    }

    private fun queryOf(path: String): Map<String, String> =
        path.substringAfter('?', "")
            .split('&')
            .mapNotNull {
                val k = it.substringBefore('=', "")
                if (k.isEmpty()) null else k to it.substringAfter('=', "")
            }
            .toMap()

    private fun listResponse(q: Map<String, String>): JSONObject {
        val accountId = q["accountId"]?.toLongOrNull() ?: 0L
        val allReceive = q["allReceive"] == "1"
        val mailbox = q["mailbox"].orEmpty()
        val category = q["category"].orEmpty()
        val keyword = java.net.URLDecoder.decode(q["text"].orEmpty(), "UTF-8")
        var list = sorted().filter { allReceive || accountId == 0L || it.accountId == accountId }
        when (mailbox) {
            "unread" -> list = list.filter { it.mailbox == "receive" && it.unread }
            "starred" -> list = list.filter { it.mailbox == "receive" && it.starred }
            "sent", "draft" -> list = emptyList()
            else -> if (mailbox.isNotEmpty()) list = list.filter { it.mailbox == mailbox }
        }
        if (category.isNotEmpty()) list = list.filter { it.category == category }
        if (keyword.isNotBlank()) {
            list = list.filter {
                it.subject.contains(keyword, true) || it.preview.contains(keyword, true) ||
                    it.name.contains(keyword, true) || it.from.contains(keyword, true)
            }
        }
        // neighbor 查询：size=1 + emailId，按时间方向取相邻一封
        val neighborOf = q["emailId"]?.toLongOrNull() ?: 0L
        if (neighborOf != 0L && q["size"] == "1") {
            val all = sorted()
            val i = all.indexOfFirst { it.id == neighborOf }
            val next = if (q["timeSort"] == "1") i - 1 else i + 1
            val only = all.getOrNull(next)
            return JSONObject().put(
                "list", JSONArray().apply { if (only != null) put(mailJson(only)) },
            ).put("total", if (only == null) 0 else 1)
        }
        val page = q["page"]?.toIntOrNull() ?: 1
        val size = q["size"]?.toIntOrNull() ?: 20
        val from = ((page - 1) * size).coerceIn(0, list.size)
        val to = (from + size).coerceAtMost(list.size)
        return JSONObject()
            .put("list", JSONArray().apply { list.subList(from, to).forEach { put(mailJson(it)) } })
            .put("total", list.size)
    }

    private fun counts(byAccount: Boolean, accountId: Long): Map<String, Int> {
        val scope = mails.filter { !byAccount || accountId == 0L || it.accountId == accountId }
        return mapOf(
            "inbox" to scope.count { it.mailbox == "receive" },
            "unread" to scope.count { it.unread },
            "starred" to scope.count { it.starred },
            "spam" to scope.count { it.mailbox == "spam" },
            "deleted" to scope.count { it.mailbox == "deleted" },
            "sent" to 0, "draft" to 0,
        )
    }

    private fun menuCounts(q: Map<String, String>): JSONObject {
        val allReceive = q["allReceive"] == "1"
        val accountId = q["accountId"]?.toLongOrNull() ?: 0L
        val c = counts(!allReceive, accountId)
        return JSONObject(c)
    }

    private fun categoryCounts(q: Map<String, String>): JSONObject {
        val allReceive = q["allReceive"] == "1"
        val accountId = q["accountId"]?.toLongOrNull() ?: 0L
        val scope = mails.filter {
            (allReceive || accountId == 0L || it.accountId == accountId) && it.mailbox == "receive"
        }
        val out = JSONObject()
        listOf("primary", "social", "promotion", "updates", "ads", "spam").forEach {
            out.put(it, scope.count { m -> m.category == it })
        }
        return out
    }

    private fun userJson(): JSONObject = JSONObject()
        .put("userId", 1001)
        .put("email", accounts[0].email)
        .put("name", "Demo Lin")
        .put("signature", "— 发自 OTSMail 演示账户")
        .put("sendOnEnter", "enter")
        .put("avatar", "")
        .put("twoFactor", false)
        .put("twoFactorRequired", false)
        .put("permKeys", JSONArray())
        .put("type", -1)
        .put("account", JSONObject().put("accountId", accounts[0].id).put("email", accounts[0].email))

    private fun accountsJson(): JSONArray = JSONArray().apply {
        accounts.forEach {
            put(JSONObject().put("accountId", it.id).put("email", it.email).put("name", it.name))
        }
    }

    private fun contactsJson(): JSONObject = JSONObject().put(
        "list",
        JSONArray().apply {
            accounts.drop(1).forEach {
                put(JSONObject().put("name", it.name).put("email", it.email).put("groupName", "Demo"))
            }
            put(JSONObject().put("name", "Priya Raman").put("email", "priya.raman@example.net"))
            put(JSONObject().put("name", "Tomas Lindqvist").put("email", "tomas.lindqvist@example.se"))
        },
    )

    /** [Api.raw] 的唯一入口：按 path 返回和真实服务端同形的 {code,message,data}。 */
    fun respond(method: String, path: String, body: JSONObject?): JSONObject {
        val p = path.substringBefore('?')
        val q = queryOf(path)
        val data: Any = when (p) {
            "/my/loginUserInfo" -> userJson()
            "/account/list" -> accountsJson()
            "/email/list" -> listResponse(q)
            "/star/list" -> JSONObject()
                .put("list", JSONArray().apply { sorted().filter { it.starred }.forEach { put(mailJson(it)) } })
                .put("total", sorted().count { it.starred })
            "/email/detail" -> detailJson(q["emailId"]?.toLongOrNull() ?: 0L)
            "/email/restore" -> {
                val id = body?.optLong("emailId") ?: q["emailId"]?.toLongOrNull() ?: 0L
                mails.replaceAll { if (it.id == id && it.mailbox == "deleted") it.copy(mailbox = "receive") else it }
                JSONObject()
            }
            "/email/spamCancel" -> {
                val id = body?.optLong("emailId") ?: q["emailId"]?.toLongOrNull() ?: 0L
                // 垃圾箱列表按 category=spam 过滤，取消标记要同时把分类拨回普通
                mails.replaceAll { if (it.id == id && it.mailbox == "spam") it.copy(mailbox = "receive", category = "primary") else it }
                JSONObject()
            }
            "/email/clearDeleted" -> {
                val n = mails.count { it.mailbox == "deleted" }
                mails.removeAll { it.mailbox == "deleted" }
                JSONObject().put("total", n)
            }
            "/email/menu-counts" -> menuCounts(q)
            "/email/category-counts" -> categoryCounts(q)
            "/contact/list" -> contactsJson()
            "/my/2fa/status" -> JSONObject().put("enabled", false).put("required", false)
            "/setting/websiteConfig" -> JSONObject()
                // 登录页的第三方入口按这些开关渲染；演示包给出 GitHub/Google，方便实拍与文档配图
                .put("githubSwitch", true)
                .put("githubClientId", "demo-github-client-id")
                .put("githubCallbackUrl", "otosmail-demo://oauth/github")
                .put("googleSwitch", true)
                .put("googleClientId", "demo-google-client-id")
                .put("googleCallbackUrl", "otosmail-demo://oauth/google")
                .put("r2Domain", "")
                .put("regKey", 0)
                .put("siteName", "OTSMail Demo")
            else -> JSONObject()
        }
        return JSONObject().put("code", 200).put("message", "ok").put("data", data)
    }
}
