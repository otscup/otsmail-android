package com.otscup.otsmail.data

/**
 * FCM 推送脚手架（客户端与服务端设备接口均已接入）。
 *
 * 现状（2026-11 核对，服务端接口已上线）：
 * - google-services.json 已放在 app/（项目 otsmail-app，包名 com.otscup.otsmail），
 *   firebase-messaging 依赖与 com.google.gms.google-services 插件已接入。
 * - 服务端已提供 Android 设备 token 的登记/注销接口，[Api.pushRegister] /
 *   [Api.pushUnregister] 走的就是下面这套约定。
 * - 登记/注销失败仍然只写 logcat + 退避（见 [PushManager]），不影响任何界面。
 *
 * 服务端接口约定：
 * 1. POST   /push/device        { token, platform: "android", deviceId, appVersion }
 * 2. DELETE /push/device?token=...
 * 3. 收信时通过 FCM HTTP v1 发 data 消息 { emailId, type, from, name, subject, snippet }
 */
object PushScaffold {
    /** 关掉时 App 不注册、不请求 token，也不发网络请求。 */
    const val ENABLED = true

    /** 与上面约定一致的设备登记路径（相对 [Api.base]）。 */
    const val REGISTER_PATH = "/push/device"

    /** token 拿到后应上报的字段名，服务端联调时按这个对。 */
    val REGISTER_FIELDS = listOf("token", "platform", "deviceId", "appVersion")
}
