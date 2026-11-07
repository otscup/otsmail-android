package com.otscup.otsmail.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.otscup.otsmail.BuildConfig
import com.otscup.otsmail.MainActivity
import com.otscup.otsmail.R
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ExtImapClient
import com.otscup.otsmail.data.ExtLocalMail
import com.otscup.otsmail.data.ExtProviders
import com.otscup.otsmail.data.ExtServer
import com.otscup.otsmail.data.ExtSync
import com.otscup.otsmail.data.ExtSyncError
import com.otscup.otsmail.data.PushManager
import com.otscup.otsmail.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 按**账户类型**判定，不是按设备一刀切：
 * - otsmail 自己的邮件继续走服务端 FCM，与这里无关；
 * - 第三方账户默认一律挂 IMAP IDLE，不管设备有没有 GMS / 能不能拿到 token。
 *
 * 原因：服务端目前只为 otsmail 邮件下发 FCM，第三方邮件没有服务端触发点。
 * 若按「有 token 就不挂 IDLE」，有 GMS 的设备就永远收不到第三方邮件提醒。
 * 只有 [SERVER_EXT_FCM_SUPPORTED] 为 true（服务端补上第三方触发点）时，
 *「自动」模式才允许改走 FCM；[PushSettings.extPushMode] 另提供强制 IDLE / 强制 FCM
 * 两个对照开关，供真机验证。
 */
object ExtIdleManager {

    private const val TAG = "ExtIdle"
    private const val SP = "otsmail_ext_idle"
    private const val KEY_MODE = "mode"
    private const val KEY_AT = "modeAt"
    internal const val MODE_FCM = "fcm"
    internal const val MODE_IDLE = "idle"
    private const val MODE_TTL_MS = 12 * 60 * 60 * 1000L

    /**
     * 服务端是否已支持「第三方邮件」的 FCM 下发。
     * 现在没有：服务端只在 otsmail 自己收信时下发，所以这里必须是 false，
     * 保证第三方账户一律走 IDLE。等服务端补上第三方触发点后再改成 true。
     */
    private const val SERVER_EXT_FCM_SUPPORTED = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var deciding = false

    /** App 启动 / 登录后调用；重复调用幂等。 */
    fun startIfNeeded(context: Context) {
        val app = context.applicationContext
        if (BuildConfig.DEMO) return
        if (Session(app).token.isEmpty() || Api.token.isEmpty()) {
            ExtIdleService.stop(app)
            return
        }
        if (deciding) return
        deciding = true
        scope.launch {
            try {
                val mode = resolveMode(app)
                val accounts = extAccountIdsOrNull()
                Log.i(
                    TAG,
                    "第三方邮件提醒方式=$mode（设置=${PushSettings.extPushMode(app)}，" +
                        "服务端支持外部邮箱 FCM=$SERVER_EXT_FCM_SUPPORTED）账户=" +
                        (accounts?.size ?: "获取失败") + " → " +
                        if (mode == MODE_IDLE) "挂 IMAP IDLE" else "等 FCM 触发，不挂 IDLE",
                )
                if (mode != MODE_IDLE) {
                    ExtIdleService.stop(app)
                } else if (accounts == null) {
                    // 弱网拿不到账户列表：仍然启动服务，让它自己退避重试并在网络恢复时直接连上；
                    // 绝不能因为一次接口失败就停掉提醒（P3）
                    Log.w(TAG, "账户列表暂时取不到（弱网/服务端不可达），先启动 IDLE 服务等它重试")
                    ExtIdleService.start(app)
                } else if (accounts.isNotEmpty()) {
                    ExtIdleService.start(app)
                } else {
                    // 服务端明确返回「没有账户」：这才是真的不需要常驻连接
                    Log.i(TAG, "没有已绑定的外部账户，不启动 IDLE 服务")
                    ExtIdleService.stop(app)
                }
            } finally {
                deciding = false
            }
        }
    }

    fun stop(context: Context) {
        ExtIdleService.stop(context.applicationContext)
    }

    /** 用户在外部邮箱设置里改了提醒方式：立刻按新设置重启/停止 IDLE。 */
    fun applyModeChange(context: Context) {
        startIfNeeded(context)
    }

    /**
     * FCM token 变化时调用。只重新判定一次：默认（第三方邮件无服务端 FCM）仍保持 IDLE，
     * 不会因为拿到 token 就把第三方邮件的长连接关掉。
     */
    fun onFcmTokenAvailable(context: Context) {
        startIfNeeded(context.applicationContext)
    }

    /** 服务运行期读缓存，避免在 Service 里做网络/token 判定。 */
    internal fun currentMode(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_IDLE).orEmpty().ifEmpty { MODE_IDLE }

    /** 读设置决定方式；「自动」只在服务端支持第三方邮件 FCM 时才看设备 token。 */
    internal suspend fun resolveMode(context: Context): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(SP, Context.MODE_PRIVATE)

        val mode = when (PushSettings.extPushMode(app)) {
            PushSettings.EXT_PUSH_IDLE -> MODE_IDLE
            PushSettings.EXT_PUSH_FCM -> MODE_FCM
            else -> {
                if (!SERVER_EXT_FCM_SUPPORTED) {
                    MODE_IDLE
                } else {
                    val at = prefs.getLong(KEY_AT, 0L)
                    val cached = prefs.getString(KEY_MODE, "").orEmpty()
                    if (cached.isNotEmpty() && System.currentTimeMillis() - at < MODE_TTL_MS) {
                        cached
                    } else {
                        val token = PushManager.ensureToken(app)
                        if (token.isNullOrEmpty()) MODE_IDLE else MODE_FCM
                    }
                }
            }
        }
        prefs.edit().putString(KEY_MODE, mode).putLong(KEY_AT, System.currentTimeMillis()).apply()
        return mode
    }

    /** 当前绑定的第三方账户 id；网络失败返回空（服务不启动，下次再试）。 */
    internal suspend fun extAccountIds(): List<Long> = extAccountIdsOrNull().orEmpty()

    /**
     * 同上，但区分「没有账户」（返回空 list）与「拿不到账户列表」（返回 null）。
     * 弱网导致 null 时绝不能当成「全部解绑」去停服务，否则提醒会静默消失。
     */
    internal suspend fun extAccountIdsOrNull(): List<Long>? = runCatching {
        val arr = Api.extAccounts()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optLong("ext_id", 0L)?.takeIf { it > 0 }
        }
    }.getOrNull()

    /** 绑定/解绑/登录/手动刷新后调用：让常驻服务立刻对齐账户，不用每分钟轮询接口。 */
    fun onAccountsChanged(context: Context) {
        val app = context.applicationContext
        // 用户选「只用设备推送」时服务本就不该在跑，别为一个对齐请求把它拉起来闪一条通知
        if (currentMode(app) != MODE_IDLE) return
        val intent = Intent(app, ExtIdleService::class.java).setAction(ExtIdleService.ACTION_ACCOUNTS_CHANGED)
        runCatching { ContextCompat.startForegroundService(app, intent) }
            .onFailure { Log.w(TAG, "通知 IDLE 服务对齐账户失败：${it.message}") }
    }
}

/**
 * IMAP IDLE 常驻前台服务（同一 App 进程）。
 *
 * 每个账户一条连接/协程；断线用指数退避（5s→10s→20s…上限 10 分钟，带抖动），
 * 登录成功即重置退避。Doze 期间主动断开摊尸的 socket，退出 Doze / 网络恢复 / 亮屏
 * 都会重建连接；进程被系统杀掉后由 START_STICKY 拉起（Android 15 的 dataSync
 * 前台服务有 6 小时/天配额，配额用尽会回调 onTimeout，这里只能停止并等下次启动）。
 */
class ExtIdleService : Service() {

    companion object {
        private const val TAG = "ExtIdle"
        private const val NOTIF_ID = 9001
        /** 绑定/解绑/手动刷新后由 App 主动触发，替代原来每分钟一次的账户轮询 */
        internal const val ACTION_ACCOUNTS_CHANGED = "com.otscup.otsmail.ext.ACCOUNTS_CHANGED"
        // 每 60 秒重发一次 IDLE（RFC 只要求 < 29 分钟）。周期结束在同一连接上查一次 STATUS UIDNEXT：
        // 会推 EXISTS 的服务器仍然秒级；QQ 这类只回 `+ idling` 不推的，最长 1 分钟也能发现新邮件。
        private const val IDLE_CYCLE_MS = 60 * 1000L
        private const val POLL_FALLBACK_MS = 15 * 60 * 1000L // 服务器不支持 IDLE 时的轮询间隔
        private const val DOZE_RETRY_MS = 60 * 1000L
        private const val BACKOFF_BASE_MS = 5_000L
        private const val BACKOFF_MAX_MS = 10 * 60 * 1000L
        private const val NOTIFY_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        /** 启动服务与网络回调常常同时触发重建，2 秒内合并成一次，避免同一账户短暂挂两条连接。 */
        private const val RESTART_DEBOUNCE_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, ExtIdleService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "启动 IDLE 前台服务失败（后台限制）：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ExtIdleService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    @Volatile
    private var destroyed = false

    private lateinit var power: PowerManager
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var idleReceiver: BroadcastReceiver? = null

    private val restartLock = Any()
    private var lastRestartAt = 0L

    /** 账户对齐协程：同一时刻只保留一个（事件可能短时间连着来） */
    private var accountsJob: Job? = null

    /** 触发一次账户对齐；delayMs 用于弱网拉取失败后的退避重试，forceRestart 用于网络恢复/退出 Doze */
    private fun checkAccounts(reason: String, delayMs: Long = 0L, forceRestart: Boolean = false) {
        if (destroyed) return
        accountsJob?.cancel()
        accountsJob = scope.launch { alignAccounts(reason, delayMs, forceRestart) }
    }

    override fun onCreate() {
        super.onCreate()
        // 进程被 START_STICKY 重新拉起时 Api.token/base 还没恢复
        Session(applicationContext).apply()

        power = getSystemService(PowerManager::class.java)
        startInForeground(0)
        registerNetwork()
        registerIdleMode()

        if (!shouldRun()) {
            stopSelf()
            return
        }
        // 账户对齐改成事件驱动（启动 / 网络恢复 / 退 Doze / 绑定解绑 / 手动刷新），
        // 待机时不再每分钟请求一次 /ext/accounts（原来 60 次/小时 = 1,440 次/天）
        checkAccounts("服务启动", forceRestart = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ACCOUNTS_CHANGED) {
            checkAccounts("账户变更")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        idleReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    /** Android 15：dataSync 前台服务每天 6 小时配额用尽后必须停。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "IDLE 前台服务超时（系统 dataSync 配额），先停止，下次启动再恢复")
        stopSelf()
    }

    private fun shouldRun(): Boolean {
        if (BuildConfig.DEMO) return false
        if (Session(applicationContext).token.isEmpty()) return false
        // 只看「第三方邮件提醒方式」，不再看设备有没有 FCM token：
        // 服务端不给第三方邮件下发 FCM，有 token 也必须挂 IDLE。
        return ExtIdleManager.currentMode(applicationContext) == ExtIdleManager.MODE_IDLE
    }

    private fun startInForeground(accountCount: Int) {
        val builder = Notification.Builder(this, sharedChannelId())
            .setSmallIcon(R.drawable.ic_notif_at)
            .setContentTitle("外部邮箱实时收取服务")
            .setContentText(foregroundText(accountCount, false))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setGroup("extIdle")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, NOTIF_ID + 1,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        data = Uri.parse("otsmail://extIdle")
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateForeground(count: Int, paused: Boolean = false) {
        if (destroyed) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, ongoingNotification(count, paused))
        }
    }

    /**
     * 常驻通知只说明「服务在替你保持邮箱连接」，避免被误认为是一封新邮件；
     * 真正的新邮件由另外的通知渠道单独弹出。
     */
    private fun foregroundText(count: Int, paused: Boolean): String {
        // 通知权限被系统关掉时（典型场景：重装被重置 app-op），服务在跑也不会弹任何提醒，
        // 常驻通知必须说清楚，不能只显示「保持连接中」造成误解
        if (!PushNotifications.enabled(this)) {
            return if (paused) {
                "新邮件提醒不会弹出（通知权限未开启；服务已暂停）"
            } else {
                "新邮件提醒不会弹出：通知权限未开启，请到系统设置里为本应用打开通知"
            }
        }
        return when {
            paused -> "已暂停（系统省电模式），退出后自动恢复"
            count > 0 -> "$count 个外部邮箱保持连接中，新邮件到达会单独提醒"
            else -> "正在建立 IMAP 连接，仅用于新邮件即时提醒"
        }
    }

    private fun ongoingNotification(count: Int, paused: Boolean): Notification =
        Notification.Builder(this, sharedChannelId())
            .setSmallIcon(R.drawable.ic_notif_at)
            .setContentTitle("外部邮箱实时收取服务")
            .setContentText(foregroundText(count, paused))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setGroup("extIdle")
            .build()

    private fun sharedChannelId(): String {
        // 常驻服务通知走现有「静音」渠道（四个渠道之一），不会因为服务启动响铃/震动；
        // 新邮件通知仍用 PushSettings.channelId() 的当前选择。
        PushNotifications.ensureChannel(this)
        return PushNotifications.CHANNEL_SILENT
    }

    private fun registerNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络恢复：socket 基本已死，全部重建；同时顺带校验账户（事件驱动，不轮询）
                if (!destroyed) checkAccounts("网络恢复", forceRestart = true)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
            .onFailure { Log.w(TAG, "注册网络回调失败：${it.message}") }
    }

    private fun registerIdleMode() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (destroyed) return
                if (power.isDeviceIdleMode) {
                    // Doze 会冻结网络：主动断开，避免挂着一个已经死掉的 socket
                    Log.i(TAG, "进入 Doze，暂停 IDLE")
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                    updateForeground(0, paused = true)
                } else {
                    Log.i(TAG, "退出 Doze，重建 IDLE")
                    checkAccounts("退出 Doze", forceRestart = true)
                }
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            idleReceiver = receiver
        }.onFailure { Log.w(TAG, "注册 Doze 广播失败：${it.message}") }
    }

    /**
     * 账户对齐（事件驱动，替代原来的每分钟看门狗）：
     * - 弱网拿不到账户列表 → **保留服务**，按指数退避重试，直到拿到为止（P3）；
     * - 服务端明确返回空 → 才是「全部解绑」，停服务并写明原因；
     * - 拿到列表 → 只增删变化的连接，不惊动已经连好的账户。
     *
     * 调用点：服务启动、绑定/解绑/登录/手动刷新（[ExtIdleManager.onAccountsChanged]）、
     * 网络恢复、退出 Doze。待机期间不产生任何 /ext/accounts 请求。
     */
    private suspend fun alignAccounts(reason: String, delayMs: Long, forceRestart: Boolean) {
        var attempt = 0
        var wait = delayMs
        while (scope.isActive && !destroyed) {
            if (wait > 0) delay(wait)
            wait = 0
            if (!shouldRun()) return

            val ids = ExtIdleManager.extAccountIdsOrNull()
            if (ids == null) {
                // 弱网 / 服务端不可达：绝不能当成「全部解绑」去停止，否则提醒会静默消失
                attempt++
                val next = backoffMs(attempt)
                Log.w(TAG, "账户列表获取失败（$reason），保留 IDLE 服务，${next / 1000}s 后重试")
                wait = next
                continue
            }
            if (power.isDeviceIdleMode) {
                Log.i(TAG, "Doze 中，账户对齐延后到退出 Doze：$reason")
                return
            }
            if (ids.isEmpty()) {
                Log.i(TAG, "所有外部账户均已解绑，停止 IDLE 服务：$reason")
                stopSelf()
                return
            }
            alignConnections(ids, reason, forceRestart)
            return
        }
    }

    /** 按账户列表增删连接：forceRestart 时全部重建（网络恢复 / 退出 Doze），否则只处理变化。 */
    private fun alignConnections(ids: List<Long>, reason: String, forceRestart: Boolean) {
        if (forceRestart) {
            synchronized(restartLock) {
                val now = System.currentTimeMillis()
                if (now - lastRestartAt < RESTART_DEBOUNCE_MS) {
                    Log.i(TAG, "忽略重复重建请求：$reason")
                    return
                }
                lastRestartAt = now
            }
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        } else {
            // 解绑的账户直接断，不必等它下一轮重连时才发现
            jobs.keys.filter { it !in ids }.forEach { jobs.remove(it)?.cancel() }
        }

        val missing = ids.filter { !jobs.containsKey(it) }
        if (missing.isNotEmpty()) {
            Log.i(
                TAG,
                "账户对齐（$reason）：${if (forceRestart) "重建" else "新增"} ${missing.size} 条 IDLE 连接，" +
                    "当前共 ${jobs.size + missing.size} 个账户",
            )
            missing.forEach { extId -> jobs[extId] = scope.launch { runAccount(extId) } }
        } else if (forceRestart) {
            Log.i(TAG, "账户对齐（$reason）：连接已全部重建，共 ${jobs.size} 个账户")
        }
        updateForeground(jobs.size)
    }

    private suspend fun runAccount(extId: Long) {
        var attempt = 0
        while (scope.isActive && !destroyed) {
            // 用户在设置里改成「设备推送」时服务整体退出；与其他账户的失败无关
            if (ExtIdleManager.currentMode(applicationContext) != ExtIdleManager.MODE_IDLE) {
                stopSelf()
                return
            }
            if (power.isDeviceIdleMode) {
                delay(DOZE_RETRY_MS)
                continue
            }
            var client: ExtImapClient? = null
            try {
                val account = accountFor(extId)
                if (account == null) {
                    Log.i(TAG, "账户 #$extId 已解绑，退出 IDLE")
                    jobs.remove(extId)
                    return
                }
                val cred = Api.extCredential(extId)
                val secret = cred.optString("secret")
                if (secret.isEmpty()) throw IllegalStateException("服务端没有返回可用授权码")
                val providerId = cred.optString("provider").ifEmpty { account.optString("provider") }
                val host = cred.optString("imapHost").ifEmpty { account.optString("imap_host") }
                val port = cred.optInt("imapPort", 0).takeIf { it > 0 } ?: account.optInt("imap_port", 993)
                val server = ExtServer(
                    host = host,
                    port = port,
                    secure = port == 993,
                    needsId = ExtProviders.byId(providerId)?.imap?.needsId == true,
                )

                client = ExtImapClient(server, account.optString("address"), secret)
                // IDLE 必须 SELECT（读写选中）才能收到 QQ 的新邮件 EXISTS；
                // 拉取侧仍用 EXAMINE，不会改动已读标记。
                val box = client.login(readOnly = false)
                attempt = 0

                if (!box.idle) {
                    // 服务器没在 CAPABILITY 里给 IDLE：保持同一条已登录连接，按 15 分钟轮询
                    Log.i(TAG, "ext=$extId 不支持 IDLE，降级为 15 分钟轮询")
                    syncAndNotify(extId)
                    delay(POLL_FALLBACK_MS)
                    continue
                }

                Log.i(TAG, "IDLE 已挂上 ext=$extId host=$host:$port")
                // 登录时的 UIDNEXT 作为基线：IDLE 不推 EXISTS 的服务器靠周期后的 STATUS 判断有没有新邮件
                var baselineUidNext = box.uidNext
                while (scope.isActive && !destroyed) {
                    val newMail = client.idleWait(IDLE_CYCLE_MS) { line ->
                        // 只记 untagged 状态行，方便确认服务器到底推了什么（不发正文/凭据）
                        Log.d(TAG, "IDLE 收到 ext=$extId：$line")
                    }
                    var changed = newMail
                    if (newMail) {
                        Log.i(TAG, "IDLE 唤醒 ext=$extId，触发一轮同步")
                    } else {
                        // 兜底：同一条已登录连接上问 UIDNEXT，变了就同步（不重新 LOGIN）
                        val latest = client.statusUidNext()
                        if (latest > baselineUidNext) {
                            Log.i(TAG, "周期检查发现新邮件 ext=$extId uidNext=$baselineUidNext→$latest，触发一轮同步")
                            changed = true
                        } else {
                            Log.d(TAG, "周期检查无变化 ext=$extId uidNext=$latest")
                        }
                    }
                    if (changed) {
                        // 同步失败不推进基线：下一轮 STATUS 还会发现同一封，继续重试
                        if (syncAndNotify(extId)) {
                            baselineUidNext = client.statusUidNext()
                        }
                    }
                    if (power.isDeviceIdleMode) {
                        Log.i(TAG, "Doze 介入，ext=$extId 断开 IDLE")
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                val wait = backoffMs(attempt)
                Log.w(TAG, "ext=$extId IDLE 断开，${wait / 1000}s 后重连：${ExtSyncError.reason(e)}")
                delay(wait)
            } finally {
                runCatching { client?.close() }
            }
        }
    }

    /**
     * 只通知「确实是新的」那一批：首次绑定 / 游标重置会一次补拉最多 40 封历史邮件，
     * 全部弹通知会刷屏；这里按未读 + 24 小时内过滤，历史补拉只进列表。
     *
     * 只同步**触发这次唤醒的那一个账户**（P2）：原来一封新邮件会让所有外部账户一起重登 IMAP
     * （N 个账户 × 每轮 N 次登录 = N²），现在只登录收到变更的那条连接对应的账户。
     */
    private suspend fun syncAndNotify(extId: Long): Boolean {
        val report = try {
            ExtSync.runOnce(applicationContext, limit = 20, onlyExtId = extId)
        } catch (e: Throwable) {
            Log.w(TAG, "IDLE 触发的同步失败 ext=$extId：${ExtSyncError.reason(e)}")
            return false
        }
        val now = System.currentTimeMillis()
        // 只通知本轮真正新插入的行：本地已存在的邮件 upsert 返回 false，不会进 newMails；
        // 游标回退导致的历史重扫在 ExtSync 里已经把 newMails 清空
        report.newMails.forEach { mail ->
            if (mail.seen || now - mail.dateMs > NOTIFY_MAX_AGE_MS) return@forEach
            if (!PushDedupe.decideExt(applicationContext, mail.extId, mail.messageId)) return@forEach
            showExtNotification(mail)
        }
        return true
    }

    private fun showExtNotification(mail: ExtLocalMail) {
        PushNotifications.showExtMail(
            this,
            mail.localId,
            mail.fromAddr,
            mail.fromName,
            mail.subject,
            mail.snippet,
        )
    }

    /**
     * 取账户信息。**接口失败直接抛异常**：runAccount 会按网络问题退避重连，
     * 绝不能把「弱网拿不到账户列表」当成「用户已解绑」而永久退出这个账户的连接（P3）。
     * 只有服务端明确返回了列表、里面确实没有这个账户，才返回 null（=真的解绑）。
     */
    private suspend fun accountFor(extId: Long): JSONObject? {
        val arr: JSONArray = Api.extAccounts()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("ext_id", 0L) == extId) return o
        }
        return null
    }

    private fun backoffMs(attempt: Int): Long {
        val base = BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 10)
        val capped = base.coerceAtMost(BACKOFF_MAX_MS)
        // ±25% 抖动，避免多账户同时重连把服务器打醒
        val jitter = (capped * 0.25 * (Math.random() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(1_000L)
    }
}
