package com.otscup.otsmail.data

/**
 * 收件箱合并后的一行。
 *
 * 两类数据来源不同（服务端 `email` 表 vs 本地 `otsmail_ext.db`），这里只做「显示层合并」：
 * 服务端仍然按它自己的分页拉取，第三方邮件由本地库按时间窗口补齐，两边都不改写入路径。
 */
data class MergedMail(
    /** LazyColumn 的稳定 key：`s:<emailId>` / `x:<extId>:<localId>` */
    val key: String,
    val timeMs: Long,
    /** 交给 TimeFmt 的原始时间字符串（服务端是 `yyyy-MM-dd HH:mm:ss`，第三方是 epoch ms） */
    val timeText: String,
    val server: MailItem? = null,
    val ext: ExtLocalMail? = null,
) {
    val isExt: Boolean get() = ext != null
}

/**
 * 合并策略：**内存合并 + 服务端分页边界对齐**。
 *
 * - 服务端分页保持不变（[com.otscup.otsmail.ui.MailCache] 的第 1..N 页照旧），不改成双源分页后再拼，
 *   否则两边翻页步长不同，滚动到底会重复或跳序。
 * - 第三方邮件是本地 SQLite 查询，代价低，按「已加载的服务端最旧一封」做时间下界：
 *   服务端还有下一页时，只补进这个时间点及更新的第三方邮件；服务端全部加载完后才放开整个本地库。
 * - 这样新翻出来的一页只会在**列表尾部追加**更早的行，已经看到的位置不会跳动。
 * - 排序统一按时间倒序；时间相同时保持服务端在前（sortedWith 是稳定排序）。
 */
object InboxMerge {

    fun merge(
        server: List<MailItem>,
        ext: List<ExtLocalMail>,
        serverEpoch: (MailItem) -> Long?,
        serverHasMore: Boolean,
    ): List<MergedMail> {
        val serverRows = ArrayList<MergedMail>(server.size)
        // 服务端列表本身已按时间倒序：从旧往新回填，解析失败的沿用「下一封（更旧那封）」的时间，
        // 再配合稳定排序，就能保证这类脏数据不会插到别的位置
        var nextGood = 0L
        for (i in server.indices.reversed()) {
            val m = server[i]
            val ms = serverEpoch(m) ?: nextGood
            if (ms > 0) nextGood = ms
            serverRows.add(MergedMail("s:${m.emailId}", ms, m.time, server = m))
        }
        serverRows.reverse()

        // 服务端还有下一页时，第三方邮件只补到「已加载窗口」的下界；否则放开整库
        val floorMs = if (serverHasMore && serverRows.isNotEmpty()) {
            serverRows.minOf { it.timeMs }
        } else {
            0L
        }

        val extRows = ext
            .asSequence()
            .filter { it.localId > 0 }
            .filter { floorMs <= 0 || it.dateMs >= floorMs }
            .map { MergedMail("x:${it.extId}:${it.localId}", it.dateMs, it.dateMs.toString(), ext = it) }
            .toList()

        val all = ArrayList<MergedMail>(serverRows.size + extRows.size)
        all.addAll(serverRows)
        all.addAll(extRows)
        all.sortWith(compareByDescending<MergedMail> { it.timeMs })
        return all
    }
}
