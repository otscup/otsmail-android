package com.otscup.otsmail.data

/**
 * FCM 推送脚手架（客户端已接入，服务端设备接口还没实现）。
 *
 * 现状（已核对）：
 * - google-services.json 已放在 app/（项目 otsmail-app，包名 com.otscup.otsmail），
 *   firebase-messaging 依赖与 com.google.gms.google-services 插件已接入。
 * - 服务端 otsmail 目前只有 Web Push（VAPID），即 `mail-worker/src/api/push-api.js` 的
 *   `/push/key`、`/push/subscribe`，供浏览器 Service Worker 使用；
 *   还没有 Android 设备 token 的登记/下发接口，所以 [Api.pushRegister] 现在会收到 404。
 * - 因此客户端把失败全部静默处理 + 退避（见 [PushManager]），不影响任何界面；
 *   服务端补上接口后无需改客户端即可生效。
 *
 * 服务端仍需补齐：
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
