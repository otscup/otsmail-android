package com.otscup.otsmail.data

/**
 * 本机验证码识别：纯本地正则，不读网络、不上传正文，识别结果只用来提示「复制」。
 *
 * 规则是「关键词 + 数字 + 排除项」三段式，缺一不可：
 * 1) 候选：4–8 位连续数字，且左右不能紧邻字母/数字（避免 `SKU123456`、9 位以上长编号被切一段）；
 * 2) 命中：同一句（按标点/换行切段）里必须出现验证码关键词，且距离够近；
 * 3) 排除：同一句里出现订单号/金额/电话/日期等词，且比关键词离数字更近时，判定为误判。
 *
 * 只做「提示」，不做自动填入；识别不到就不显示按钮，不猜。
 */
object VerificationCode {

    data class Hit(
        /** 命中的数字串（去掉首尾空白，保留用户看到的原样） */
        val code: String,
        /** 命中的关键词，仅用于调试与解释命中原因 */
        val keyword: String,
        /** 关键词到数字的距离（字符数），越近可信度越高 */
        val distance: Int,
    )

    /** 单句窗口：向前最多 48 字符、向后 32 字符，超出按「不在附近」处理 */
    private const val WINDOW_BEFORE = 48
    private const val WINDOW_AFTER = 32

    /** 强关键词：出现即可信 */
    private const val STRONG_MAX_DISTANCE = 24

    /** 弱关键词（code/pin）容易出现在普通句子，必须贴得更近 */
    private const val WEAK_MAX_DISTANCE = 12

    /** 句内切段符：中文逗号也算边界，避免「验证码1234，订单号56789012」互相干扰 */
    private val SEGMENT_BREAK = charArrayOf(
        '\n', '\r', '。', '！', '？', '；', '，', '、', ';', '!', '?', ',',
    )

    private val CANDIDATE = Regex("(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9A-Za-z])")

    private val STRONG = listOf(
        "验证码", "校验码", "验证代码", "动态码", "确认码", "认证码", "安全码", "短信密码",
        "一次性密码", "一次性验证码", "登录码", "激活码",
        "verification code", "verification pin", "one-time password", "one-time code",
        "one time password", "one time code", "otp", "passcode", "security code",
        "login code", "authentication code", "auth code", "2fa", "two-factor",
    )

    private val WEAK = listOf("code", "pin", "密码", "口令")

    /** 编号/金额/电话：任何长度都排除 */
    private val EXCLUDE_ALWAYS = listOf(
        "订单号", "订单编号", "订单", "单号", "编号", "流水号", "运单", "快递", "物流",
        "tracking", "order number", "order no", "order #", "invoice", "reference",
        "sku", "序列号", "barcode", "条形码", "邮编", "身份证", "证件号", "银行卡", "卡号",
        "account number", "iban", "vip",
        "金额", "价格", "合计", "总计", "优惠", "折扣", "付款", "支付", "账单", "余额",
        "转账", "发票", "积分", "里程", "金币", "红包", "优惠券",
        "电话", "手机", "热线", "客服", "tel", "telephone", "phone", "mobile", "fax",
        "微信", "qq",
    )

    /** 日期词只对 4 位候选生效：8 位验证码旁边写「日/月」不应被误伤 */
    private val EXCLUDE_DATE = listOf("年", "月", "日", "日期", "date", "year", "年度", "星期")

    /** 数字后紧跟这些单位时按数量/金额/尺寸处理 */
    private val UNIT_SUFFIX = setOf(
        '元', '块', '万', '亿', '件', '个', '条', '次', '页', '岁', '折', '%', '％',
        '克', '斤', '米', '寸', '天', '秒',
    )

    /** 数字前紧邻这些符号时按金额处理 */
    private val MONEY_PREFIX = setOf('¥', '￥', '$', '€', '£', '＄')

    /**
     * 从任意文本里找最可信的验证码；找不到返回 null。
     * 调用方可以传「主题 + 正文」：主题写「Your verification code」、正文写数字时也能命中。
     */
    fun find(text: String): Hit? {
        if (text.isBlank()) return null
        // 调用方传 HTML 时先转纯文本：样式里的 #123456 是颜色，不是验证码
        val source = if (text.contains('<')) plainText(text) else text
        val normalized = normalize(source)
        if (normalized.isEmpty()) return null

        var best: Hit? = null
        var bestStrength = 0
        for (match in CANDIDATE.findAll(normalized)) {
            val start = match.range.first
            val end = match.range.last + 1
            val code = match.groupValues[1]
            if (isStructurallyExcluded(normalized, start, end, code)) continue

            val segStart = segmentStart(normalized, start)
            val segEnd = segmentEnd(normalized, end)
            val segment = normalized.substring(segStart, segEnd)
            val localStart = start - segStart
            val localEnd = end - segStart

            val include = nearest(segment, STRONG, localStart, localEnd)
                ?.let { Triple(it.first, it.second, 2) }
                ?: nearest(segment, WEAK, localStart, localEnd)?.let { Triple(it.first, it.second, 1) }
            if (include == null) continue
            val (keyword, distance, strength) = include
            val maxDistance = if (strength == 2) STRONG_MAX_DISTANCE else WEAK_MAX_DISTANCE
            if (distance > maxDistance) continue

            val exclude = nearest(
                segment,
                excludeWordsFor(code),
                localStart,
                localEnd,
            )
            // 排除词更近，或等距且位于数字之前（订单号123456）→ 判为误判；
            // 等距但位于数字之后（验证码123456 客服电话…）不排除，避免误伤真验证码。
            if (exclude != null && (exclude.second < distance || (exclude.second == distance && exclude.third))) {
                continue
            }

            val better = best == null ||
                strength > bestStrength ||
                (strength == bestStrength && distance < best!!.distance)
            if (better) {
                best = Hit(code, keyword, distance)
                bestStrength = strength
            }
        }
        return best
    }

    /** HTML → 纯文本：去掉 style/script/head 与注释、标签、实体，避免把样式里的数字当验证码 */
    fun plainText(html: String): String {
        if (html.isBlank()) return ""
        var s = Regex("(?is)<!--.*?-->").replace(html, " ")
        s = Regex("(?is)<(style|script|head|title)[^>]*>.*?</\\1>").replace(s, " ")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = Regex("&(#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);").replace(s, " ")
        return s.replace('\u00A0', ' ')
            .replace(Regex("[\\t\\f\\u000B]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
    }

    private fun excludeWordsFor(code: String): List<String> =
        if (code.length == 4) EXCLUDE_ALWAYS + EXCLUDE_DATE else EXCLUDE_ALWAYS

    /**
     * 全角数字转半角、折叠空白，并把空行当成句子边界：
     * 主题与正文之间的单换行要能连起来（「Your verification code」换行后跟数字），
     * 但空行隔开的引用历史必须切断，避免把旧邮件里的码当成本封的。
     * 长度会变化，但后续窗口距离都基于归一化后的字符串，保持一致即可。
     */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    in '\uFF10'..'\uFF19' -> '0' + (ch - '\uFF10')
                    '\u00A0' -> ' '
                    else -> ch
                },
            )
        }
        var s = sb.toString()
        s = Regex("\\r?\\n\\s*\\r?\\n").replace(s, "\u3002")
        return s.replace('\r', ' ').replace('\n', ' ')
    }

    private fun isStructurallyExcluded(text: String, start: Int, end: Int, code: String): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        // 紧邻金额符号
        if (before != null && before in MONEY_PREFIX) return true
        // 紧邻数量/金额/尺寸单位
        if (after != null && after in UNIT_SUFFIX) return true
        // 4 位且后面是 年/月/日 或 -/ 日期分隔：按年份/日期处理
        if (code.length == 4) {
            if (after == '年' || after == '月' || after == '日') return true
            if (after == '-' || after == '/') return true
        }
        // 7–8 位且前面是 +86 / +：按电话处理（更长的号码已被候选长度挡掉）
        if (code.length >= 7) {
            val prefix = text.substring(maxOf(0, start - 3), start)
            if (prefix.contains("+86") || prefix.trimEnd().endsWith("+")) return true
        }
        // 链接里的参数（?code=1234、utm 追踪号）不当作验证码；窗口要够长才能覆盖到协议头
        val linkWindow = text.substring(maxOf(0, start - 48), start).lowercase()
        if (linkWindow.contains("http://") || linkWindow.contains("https://") || linkWindow.contains("www.")) {
            return true
        }
        return false
    }

    /** 句内窗口起点：遇到切段符或向前 48 字符为止 */
    private fun segmentStart(text: String, index: Int): Int {
        var i = index
        var steps = 0
        while (i > 0 && steps < WINDOW_BEFORE) {
            if (text[i - 1] in SEGMENT_BREAK) break
            i--
            steps++
        }
        return i
    }

    /** 句内窗口终点：遇到切段符或向后 32 字符为止 */
    private fun segmentEnd(text: String, index: Int): Int {
        var i = index
        var steps = 0
        while (i < text.length && steps < WINDOW_AFTER) {
            if (text[i] in SEGMENT_BREAK) break
            i++
            steps++
        }
        return i
    }

    /**
     * 在句内找离候选最近的某个关键词，返回（关键词, 距离, 是否在数字之前）。
     * ASCII 关键词要求左右不是字母，避免 `decode`、`encoder` 冒充 code。
     */
    private fun nearest(
        segment: String,
        words: List<String>,
        candStart: Int,
        candEnd: Int,
    ): Triple<String, Int, Boolean>? {
        val lower = segment.lowercase()
        var best: Triple<String, Int, Boolean>? = null
        for (word in words) {
            var from = 0
            while (true) {
                val i = lower.indexOf(word, from)
                if (i < 0) break
                from = i + 1
                if (word.all { it.code < 128 }) {
                    val prev = lower.getOrNull(i - 1)
                    val next = lower.getOrNull(i + word.length)
                    if (prev != null && prev.isAsciiLetter()) continue
                    if (next != null && next.isAsciiLetter()) continue
                }
                val wordEnd = i + word.length
                val isBefore = wordEnd <= candStart
                val distance = when {
                    isBefore -> candStart - wordEnd
                    i >= candEnd -> i - candEnd
                    else -> 0
                }
                if (best == null || distance < best!!.second) best = Triple(word, distance, isBefore)
            }
        }
        return best
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
