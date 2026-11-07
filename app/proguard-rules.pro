# R8 规则：只保留 Manifest/反射边界上确实需要完整类体的类型。
# Compose、AndroidX、Firebase 的 keep 规则由各自 AAR 的 consumer-rules 提供，不在这里重复。

# FCM 消息入口（Manifest 声明，R8 全模式下显式保留方法体）
-keep class com.otscup.otsmail.push.OtsMessagingService { *; }
