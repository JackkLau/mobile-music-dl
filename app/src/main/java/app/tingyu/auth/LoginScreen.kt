package app.tingyu.auth

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tingyu.R
import app.tingyu.model.Account
import app.tingyu.ui.ActionIcon
import app.tingyu.ui.StatePanel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal enum class LoginMethod(val label: String) { Qr("扫码登录"), Web("网页登录") }

internal sealed interface QrPanel {
    data object Loading : QrPanel
    data class Ready(val bitmap: Bitmap, val scanned: Boolean = false) : QrPanel
    data object Expired : QrPanel
    data object Verifying : QrPanel
    data class Failed(val message: String, val retryVerification: Boolean = false) : QrPanel
}

internal data class WebStatus(
    val message: String = "在哔哩哔哩官方网页登录，完成后将自动验证。",
    val error: Boolean = false,
    val loading: Boolean = false,
    val verifying: Boolean = false,
)

/** Keep the four-module quiet zone and integer modules produced by ZXing. */
internal fun makeLoginQr(url: String): Bitmap {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun LoginScreen(
    method: LoginMethod, qr: QrPanel, account: Account?, webStatus: WebStatus,
    onBack: () -> Unit, onMethod: (LoginMethod) -> Unit,
    onRefresh: () -> Unit, onRetryVerify: () -> Unit,
    onWebReady: (WebView) -> Unit, onWebReleased: (WebView) -> Unit,
    onWebPage: (WebView, WebStatus) -> Unit, onWebReload: () -> Unit, onWebComplete: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
            val short = maxHeight < 350.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onClick = onBack)
                    Text("登录哔哩哔哩", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (short && account == null) TextButton(onClick = {
                        onMethod(if (method == LoginMethod.Qr) LoginMethod.Web else LoginMethod.Qr)
                    }) { Text(if (method == LoginMethod.Qr) LoginMethod.Web.label else LoginMethod.Qr.label) }
                }
                if (account != null) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        StatePanel(Icons.Rounded.CheckCircle, "登录成功", "已登录 ${account.name}",
                            stringResource(R.string.return_to_app, stringResource(R.string.app_name)), onBack)
                    }
                } else {
                    if (!short) LoginMethodTabs(method, onMethod)
                    when (method) {
                        LoginMethod.Qr -> QrLoginCard(qr, onRefresh, onRetryVerify)
                        LoginMethod.Web -> OfficialWebLogin(webStatus, onWebReady, onWebReleased, onWebPage,
                            onWebReload, onWebComplete, short)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginMethodTabs(method: LoginMethod, select: (LoginMethod) -> Unit) {
    TabRow(
        selectedTabIndex = method.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        LoginMethod.entries.forEach { tab ->
            Tab(selected = method == tab, onClick = { select(tab) }, text = { Text(tab.label) })
        }
    }
}

@Composable
internal fun QrLoginCard(qr: QrPanel, onRefresh: () -> Unit, onRetryVerify: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val title = when (qr) {
                    QrPanel.Loading -> "正在获取二维码…"
                    is QrPanel.Ready -> if (qr.scanned) "已扫码，请确认登录" else "使用哔哩哔哩扫码"
                    QrPanel.Expired -> "二维码已过期"
                    QrPanel.Verifying -> "正在验证登录状态…"
                    is QrPanel.Failed -> if (qr.retryVerification) "登录验证失败" else "扫码登录暂不可用"
                }
                Text(title, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.widthIn(max = 216.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    when (qr) {
                        is QrPanel.Ready -> if (qr.scanned) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Image(qr.bitmap.asImageBitmap(), "哔哩哔哩登录二维码",
                                modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                        }
                        QrPanel.Loading, QrPanel.Verifying -> CircularProgressIndicator()
                        else -> Icon(Icons.Rounded.QrCode2, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val hint = when (qr) {
                    is QrPanel.Ready -> if (qr.scanned) stringResource(R.string.qr_confirm_return, stringResource(R.string.app_name)) else "请用另一台已登录哔哩哔哩的设备扫描上方二维码。"
                    QrPanel.Expired -> "刷新后，用哔哩哔哩重新扫描。"
                    is QrPanel.Failed -> qr.message
                    else -> null
                }
                if (hint != null) Text(hint, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    color = if (qr is QrPanel.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (qr is QrPanel.Ready || qr is QrPanel.Failed || qr == QrPanel.Expired) {
                    Spacer(Modifier.height(12.dp))
                    if (qr is QrPanel.Failed && qr.retryVerification) {
                        Button(onClick = onRetryVerify) { Text("重新验证") }
                        TextButton(onClick = onRefresh) { Text("获取新二维码") }
                    } else {
                        OutlinedButton(onClick = onRefresh) { Text("刷新二维码") }
                    }
                }
            }
        }
    }
}
