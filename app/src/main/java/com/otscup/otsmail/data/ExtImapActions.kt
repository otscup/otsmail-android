package com.otscup.otsmail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 第三方邮件的上游写操作：全部直连 IMAP，作用在对方服务器上（不改 otsmail 服务端）。
 *
 * 能力边界：
 * - 标记已读 / 未读：UID STORE \Seen —— 所有 IMAP 服务器支持；
 * - 星标：UID STORE \Flagged —— 所有 IMAP 服务器支持；
 * - 删除：COPY 到服务器的 Trash（LIST 里 \Trash 或常见名字）后标记 \Deleted + EXPUNGE。
 *   找不到回收站时明确失败，不做永久删除。
 * - 附件下载：按 UID 重新取一次原文，用本地 MIME 解析取第 index 个附件字节。
 */
object ExtImapActions {

    private const val TAG = "ExtWrite"

    data class Deleted(val trashName: String)

    suspend fun setSeen(extId: Long, localId: Long, uid: Long, seen: Boolean) =
        withContext(Dispatchers.IO) {
            withClient(extId, readOnly = false) { imap ->
                imap.storeFlag(uid, "\\Seen", seen)
            }
            ExtMailStore.setSeen(localId, seen)
            ExtSync.changed()
            Log.i(TAG, "标记已读=$seen ext=$extId uid=$uid")
        }

    suspend fun setStarred(extId: Long, localId: Long, uid: Long, starred: Boolean) =
        withContext(Dispatchers.IO) {
            withClient(extId, readOnly = false) { imap ->
                imap.storeFlag(uid, "\\Flagged", starred)
            }
            ExtMailStore.setStarred(localId, starred)
            ExtSync.changed()
            Log.i(TAG, "星标=$starred ext=$extId uid=$uid")
        }

    suspend fun deleteToTrash(extId: Long, localId: Long, uid: Long): Deleted =
        withContext(Dispatchers.IO) {
            val trash = withClient(extId, readOnly = false) { imap ->
                imap.moveToTrash(uid)
            }
            ExtMailStore.deleteReceived(localId)
            ExtSync.changed()
            Log.i(TAG, "已删除到回收站 ext=$extId uid=$uid trash=$trash")
            Deleted(trash)
        }

    /**
     * 下载第 [index] 个附件（顺序与 MIME 解析顺序一致，也就是列表里显示的名字顺序）。
     * 走 IMAP 重新取原文，不依赖服务端归档；单附件上限 10MB，超过明确报错。
     */
    suspend fun downloadAttachment(
        context: Context,
        extId: Long,
        uid: Long,
        index: Int,
        filename: String,
    ): File = withContext(Dispatchers.IO) {
        val raw = withClient(extId, readOnly = true) { imap ->
            imap.fetch(uid).raw
        }
        val parsed = MimeParser.parse(raw, maxAttachmentBytes = 10 * 1024 * 1024)
        val att = parsed.attachments.getOrNull(index)
            ?: throw IllegalStateException("这封邮件的附件结构已变化，找不到第 ${index + 1} 个附件")
        val bytes = att.data
            ?: throw IllegalStateException("附件超过 10MB，暂不支持在应用内下载")
        val saved = Attachments.saveBytes(context, bytes, filename.ifBlank { att.filename })
        Log.i(TAG, "附件下载成功 ext=$extId uid=$uid index=$index size=${bytes.size}")
        saved
    }

    private suspend fun <T> withClient(extId: Long, readOnly: Boolean, block: (ExtImapClient) -> T): T {
        // 口令只在这一轮内存里存在，连接关掉即被回收；不打印、不落盘
        val cred = Api.extCredential(extId)
        val secret = cred.optString("secret")
        if (secret.isEmpty()) throw IllegalStateException("服务端没有返回可用的授权码，请重新绑定这个邮箱")
        val address = cred.optString("address")
        val providerId = cred.optString("provider")
        val provider = ExtProviders.byId(providerId)
        val host = cred.optString("imapHost").ifEmpty { provider?.imap?.host.orEmpty() }
        val port = cred.optInt("imapPort", 0).takeIf { it > 0 } ?: provider?.imap?.port ?: 993
        if (host.isEmpty()) throw IllegalStateException("没有可用的 IMAP 服务器，请在外部邮箱设置里手动填写")
        val server = ExtServer(
            host = host,
            port = port,
            secure = port == 993,
            needsId = provider?.imap?.needsId == true,
        )
        return ExtImapClient(server, address, secret).use { imap ->
            imap.login(readOnly = readOnly)
            block(imap)
        }
    }
}
