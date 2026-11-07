package com.otscup.otsmail.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 历史 `extPushMode` 归一化：老用户机器上可能存着 auto / idle / fcm，
 * 读取时必须映射到新的两项，且 `fcm` 只能落到「关闭」。
 */
class PushSettingsTest {

    @Test
    fun autoStaysAuto() {
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode("auto"))
    }

    @Test
    fun idleMapsToAuto() {
        // 旧的「始终用长连接」＝ 现在的「自动」：第三方仍走 IDLE，行为不变
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode("idle"))
    }

    @Test
    fun fcmMapsToOff() {
        // 旧的「只用设备推送（调试）」本来就收不到提醒，归一到「关闭」而不是「自动」，避免突然耗电
        assertEquals(PushSettings.EXT_PUSH_OFF, PushSettings.normalizeExtPushMode("fcm"))
    }

    @Test
    fun offStaysOff() {
        assertEquals(PushSettings.EXT_PUSH_OFF, PushSettings.normalizeExtPushMode("off"))
    }

    @Test
    fun missingValueDefaultsToAuto() {
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode(null))
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode(""))
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode("   "))
    }

    @Test
    fun unknownValueFallsBackToAuto() {
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode("push"))
    }

    @Test
    fun caseAndWhitespaceAreTolerated() {
        assertEquals(PushSettings.EXT_PUSH_OFF, PushSettings.normalizeExtPushMode(" FCM "))
        assertEquals(PushSettings.EXT_PUSH_AUTO, PushSettings.normalizeExtPushMode("\tAuto\n"))
        assertEquals(PushSettings.EXT_PUSH_OFF, PushSettings.normalizeExtPushMode("OFF"))
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = PushSettings.normalizeExtPushMode("fcm")
        assertEquals(once, PushSettings.normalizeExtPushMode(once))
    }
}
