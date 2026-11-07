package com.otscup.otsmail.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.otscup.otsmail.data.Api
import com.otscup.otsmail.data.ApiException
import com.otscup.otsmail.data.QrPayload
import com.otscup.otsmail.data.Session
import com.otscup.otsmail.data.friendlyError
import com.otscup.otsmail.ui.theme.OT
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private sealed interface QrOutcome {
    data object Success : QrOutcome
    data class Failure(val title: String, val detail: String) : QrOutcome
}

/**
 * 扫码确认网页端登录。
 *
 * 服务端方向是「网页端生成票据并轮询，App 用已登录会话确认」：本页只调
 * `POST /my/qr/confirm`，不会拿到 token，也不改本地会话；被登录的是网页浏览器。
 */
@Composable
fun QrScanScreen(session: Session, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = OT.colors
    val t = OT.type

    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    var scannedId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<QrOutcome?>(null) }
    var scanHint by remember { mutableStateOf("") }

    fun confirm(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                Api.qrConfirm(id)
                outcome = QrOutcome.Success
            } catch (e: Throwable) {
                outcome = when ((e as? ApiException)?.httpCode) {
                    410 -> QrOutcome.Failure("二维码已过期", "请在网页端刷新二维码后重新扫描。")
                    400 -> QrOutcome.Failure("二维码无效", "该二维码已被使用或已失效，请刷新网页后重试。")
                    401 -> QrOutcome.Failure("登录已失效", "请重新登录后再扫码确认。")
                    else -> QrOutcome.Failure("确认失败", friendlyError(e).ifEmpty { "请求失败，请稍后重试" })
                }
            } finally {
                busy = false
                scannedId = null
            }
        }
    }

    val cameraActive = granted && scannedId == null && outcome == null && !busy

    Box(
        Modifier
            .fillMaxSize()
            .background(if (granted && outcome == null) Color.Black else c.bgGrouped),
    ) {
        if (!hasCamera) {
            QrMessagePanel(
                title = "此设备没有可用相机",
                detail = "扫码确认网页端登录需要相机。你仍可以在网页端用密码或第三方登录。",
                onBack = onBack,
            )
        } else if (!granted) {
            QrMessagePanel(
                title = "需要相机权限",
                detail = "相机只用于识别网页端登录二维码，画面在设备本地处理，不会拍照或上传。",
                onBack = onBack,
                primary = if (asked) "重新授权" else "允许使用相机",
                onPrimary = {
                    asked = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                secondary = "去系统设置",
                onSecondary = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                },
            )
        } else {
            if (outcome == null) {
                if (cameraActive) {
                    CameraPreview(
                        onTicket = { id -> scannedId = id },
                        onInvalid = { reason -> scanHint = reason },
                    )
                }

                // 顶部返回：相机画面是黑底，这里不用 OTNavBar 的默认配色
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).tapArea(onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("‹", color = Color.White, fontSize = 30.sp)
                    }
                    Text(
                        "扫一扫",
                        style = t.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }

                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(260.dp)
                            .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "将网页端登录页的二维码放入框内",
                        style = t.subhead,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    if (scanHint.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            scanHint,
                            style = t.footnote,
                            color = Color(0xFFFFD60A),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Text(
                    "扫码后还需在手机上点确认，网页端才会登录",
                    style = t.footnote,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 28.dp),
                )
            }
        }

        val result = outcome
        if (result != null) {
            val failure = result as? QrOutcome.Failure
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.bgCard)
                        .padding(20.dp),
                ) {
                    Text(
                        if (failure == null) "已确认登录" else failure.title,
                        style = t.title3,
                        color = if (failure == null) c.green else c.label,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (failure == null) {
                            "网页端会自动完成登录，可以关闭此页了。"
                        } else {
                            failure.detail
                        },
                        style = t.subhead,
                        color = c.label2,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        if (failure == null) {
                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(containerColor = c.blue),
                            ) { Text("完成") }
                        } else {
                            TextButton(onClick = onBack) { Text("返回") }
                            Button(
                                onClick = {
                                    outcome = null
                                    scanHint = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = c.blue),
                            ) { Text("重新扫描") }
                        }
                    }
                }
            }
        }

        val id = scannedId
        if (id != null) {
            AlertDialog(
                onDismissRequest = { if (!busy) scannedId = null },
                title = { Text("确认登录网页端？") },
                text = {
                    Text("确认后，网页端将以 ${session.email} 登录。\n如果这不是你本人的操作，请取消。")
                },
                confirmButton = {
                    TextButton(onClick = { confirm(id) }, enabled = !busy) {
                        Text(if (busy) "确认中…" else "确认登录")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (!busy) scannedId = null }, enabled = !busy) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

@Composable
private fun QrMessagePanel(
    title: String,
    detail: String,
    onBack: () -> Unit,
    primary: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val t = OT.type
    val c = OT.colors
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = t.title3, color = c.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(detail, style = t.subhead, color = c.label2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        if (primary != null && onPrimary != null) {
            Button(
                onClick = onPrimary,
                colors = ButtonDefaults.buttonColors(containerColor = c.blue),
            ) { Text(primary) }
        }
        if (secondary != null && onSecondary != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSecondary) { Text(secondary) }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack) { Text("返回") }
    }
}

/**
 * CameraX 预览 + ZXing 解码：单线程分析、300ms 节流、命中二维码后立即停止。
 * 只回调文本，票据格式校验放在 UI 层，避免把解析异常带进相机线程。
 */
@Composable
private fun CameraPreview(onTicket: (String) -> Unit, onInvalid: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val stopped = remember { AtomicBoolean(false) }
    val lastDecodeAt = remember { AtomicLong(0L) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    if (stopped.get()) return@setAnalyzer
                    val now = System.currentTimeMillis()
                    if (now - lastDecodeAt.get() < 300L) return@setAnalyzer
                    lastDecodeAt.set(now)
                    val text = decodeQr(proxy) ?: return@setAnalyzer
                    when (val r = QrPayload.parse(text)) {
                        is QrPayload.Result.Ok -> {
                            stopped.set(true)
                            main.post { onTicket(r.id) }
                        }

                        is QrPayload.Result.Invalid -> main.post { onInvalid(r.reason) }
                    }
                } catch (_: Throwable) {
                    // 单帧失败直接丢弃，下一帧继续
                } finally {
                    proxy.close()
                }
            }
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            stopped.set(true)
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    if (plane.pixelStride != 1 || plane.rowStride < image.width) return null
    val data = ByteArray(plane.rowStride * image.height)
    plane.buffer.rewind()
    val len = minOf(plane.buffer.remaining(), data.size)
    if (len <= 0) return null
    plane.buffer.get(data, 0, len)

    var source: LuminanceSource = PlanarYUVLuminanceSource(
        data,
        plane.rowStride,
        image.height,
        0,
        0,
        image.width,
        image.height,
        false,
    )
    source = when (image.imageInfo.rotationDegrees) {
        90 -> source.rotateCounterClockwise()
        180 -> source.rotateCounterClockwise().rotateCounterClockwise()
        270 -> source.rotateCounterClockwise()
            .rotateCounterClockwise()
            .rotateCounterClockwise()

        else -> source
    }

    val reader = MultiFormatReader()
    reader.setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ),
    )
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Throwable) {
        null
    } finally {
        reader.reset()
    }
}
