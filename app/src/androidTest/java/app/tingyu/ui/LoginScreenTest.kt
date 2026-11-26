package app.tingyu.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import android.webkit.WebView
import app.tingyu.auth.LoginActivity
import app.tingyu.auth.LoginMethod
import app.tingyu.auth.LoginScreen
import app.tingyu.auth.QrLoginCard
import app.tingyu.auth.QrPanel
import app.tingyu.auth.WebStatus
import app.tingyu.auth.makeLoginQr
import app.tingyu.model.Account
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activityOpensWithQrSwitchesToOfficialWebAndReturns() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            compose.onNodeWithText("登录哔哩哔哩").assertIsDisplayed()
            compose.onNodeWithText("完成登录").assertDoesNotExist()
            compose.onNodeWithText("网页登录").performClick()
            onView(isAssignableFrom(WebView::class.java)).check(matches(isDisplayed()))
            compose.onNodeWithText("完成登录").assertIsDisplayed()
            compose.onNodeWithText("扫码登录").performClick()
            compose.onNodeWithText("完成登录").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun expiredQrCanRefreshAndDisplayedBitmapDecodesToOriginalChallenge() {
        val url = "https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=test-only"
        val bitmap = makeLoginQr(url)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        assertEquals(url, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)),
            mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text)
        compose.setContent {
            var panel by remember { mutableStateOf<QrPanel>(QrPanel.Expired) }
            TingYuTheme { QrLoginCard(panel, onRefresh = { panel = QrPanel.Ready(bitmap) }, onRetryVerify = {}) }
        }
        compose.onNodeWithText("二维码已过期").assertIsDisplayed()
        compose.onNodeWithText("刷新二维码").performScrollTo().performClick()
        compose.onNodeWithContentDescription("哔哩哔哩登录二维码").assertIsDisplayed()
    }

    @Test fun verifiedSuccessShowsAccountAndReturnsToApp() {
        var returned = false
        compose.setContent {
            TingYuTheme {
                LoginScreen(LoginMethod.Qr, QrPanel.Verifying, Account(42, "测试账号"), WebStatus(),
                    onBack = { returned = true }, onMethod = {}, onRefresh = {}, onRetryVerify = {},
                    onWebReady = {}, onWebReleased = {}, onWebPage = { _, _ -> }, onWebReload = {}, onWebComplete = {})
            }
        }
        compose.onNodeWithText("登录成功").assertIsDisplayed()
        compose.onNodeWithText("已登录 测试账号").assertIsDisplayed()
        compose.onNodeWithText("返回微乐").performScrollTo().performClick()
        assertTrue(returned)
    }
}
