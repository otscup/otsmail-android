package com.otscup.otsmail.data

import android.content.Context

/** 只用 SharedPreferences，不引 DataStore：三个字段，没有必要为它加依赖。 */
class Session(context: Context) {

    private val prefs = context.getSharedPreferences("otsmail", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var base: String
        get() = prefs.getString("base", "").orEmpty()
        set(value) = prefs.edit().putString("base", value).apply()

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(value) = prefs.edit().putString("token", value).apply()

    var accountId: Long
        get() = prefs.getLong("accountId", 0L)
        set(value) = prefs.edit().putLong("accountId", value).apply()

    var name: String
        get() = prefs.getString("name", "").orEmpty()
        set(value) = prefs.edit().putString("name", value).apply()

    var email: String
        get() = prefs.getString("email", "").orEmpty()
        set(value) = prefs.edit().putString("email", value).apply()

    /** 登录页邮箱域名（可和服务器 host 不同）；空则回退到 base 的 host。 */
    var loginDomain: String
        get() = prefs.getString("loginDomain", "").orEmpty()
        set(value) = prefs.edit().putString("loginDomain", value).apply()

    var signature: String
        get() = prefs.getString("signature", "").orEmpty()
        set(value) = prefs.edit().putString("signature", value).apply()

    var sendOnEnter: String
        get() = prefs.getString("sendOnEnter", "enter").orEmpty().ifEmpty { "enter" }
        set(value) = prefs.edit().putString("sendOnEnter", value).apply()

    /** 列表显示发件人头像（设置页开关）；关掉时改显示账户彩点。 */
    var showAvatars: Boolean
        get() = prefs.getBoolean("showAvatars", true)
        set(value) = prefs.edit().putBoolean("showAvatars", value).apply()

    /** 服务端 /my/loginUserInfo 返回的 avatar 外链；空表示没设置。 */
    var avatar: String
        get() = prefs.getString("avatar", "").orEmpty()
        set(value) = prefs.edit().putString("avatar", value).apply()

    /**
     * 本地头像（data:image/...;base64,）。服务端上传失败（比如本地 R2 没配）时兜底，
     * 设置页会明确标注「本地头像」，避免让人以为已经同步到服务端。
     */
    var avatarLocal: String
        get() = prefs.getString("avatarLocal", "").orEmpty()
        set(value) = prefs.edit().putString("avatarLocal", value).apply()

    /**
     * Android 13+ 的通知权限是否已经问过一次。只在首次登录后请求一次，
     * 拒绝后不再打扰（想开只能去系统设置）。
     */
    var notifAsked: Boolean
        get() = prefs.getBoolean("notifAsked", false)
        set(value) = prefs.edit().putBoolean("notifAsked", value).apply()

    fun apply() {
        Api.base = base
        Api.token = token
    }

    fun clear() {
        // 先注销推送设备：内部会捕获当前的 Api.token（下面就把 token 清掉了）
        PushManager.unregisterAsync(appContext)
        prefs.edit().remove("token").remove("accountId").remove("name").remove("email").apply()
        Api.token = ""
        // 退出登录/删除账户：列表与详情缓存一起清，避免换号后串数据
        MailCache.wipe()
    }
}
