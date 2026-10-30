package com.otscup.otsmail.data

import android.content.Context

/** 只用 SharedPreferences，不引 DataStore：三个字段，没有必要为它加依赖。 */
class Session(context: Context) {

    private val prefs = context.getSharedPreferences("otsmail", Context.MODE_PRIVATE)

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

    var signature: String
        get() = prefs.getString("signature", "").orEmpty()
        set(value) = prefs.edit().putString("signature", value).apply()

    var sendOnEnter: String
        get() = prefs.getString("sendOnEnter", "enter").orEmpty().ifEmpty { "enter" }
        set(value) = prefs.edit().putString("sendOnEnter", value).apply()

    fun apply() {
        Api.base = base
        Api.token = token
    }

    fun clear() {
        prefs.edit().remove("token").remove("accountId").remove("name").remove("email").apply()
        Api.token = ""
    }
}
