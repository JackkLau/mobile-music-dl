package app.tingyu.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import app.tingyu.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackNavigationTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun incomingShareOpensParserFromPlaybackHome() {
        app.onNodeWithText("播放").assertIsSelected()
        assertSystemBarAppearance(light = false)
        val shared = "【黑胶试听合輯】周杰伦_葉惠美 https://bili2233.cn/SrmWssU"
        val receiver = app.activity
        val launchIntent = receiver.intent
        try {
            app.runOnUiThread {
                receiver.startActivity(Intent(receiver, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shared)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            app.waitUntil(5000) { app.onAllNodes(androidx.compose.ui.test.hasText(shared)).fetchSemanticsNodes().isNotEmpty() }
            app.onNodeWithText("解析").assertIsSelected()
            assertSystemBarAppearance(light = true)
            app.onNodeWithText(shared).assertExists()
            app.onNodeWithText("解析链接").assertIsEnabled()
        } finally {
            // ActivityScenario matches lifecycle events against its original MAIN intent.
            // Restore it after exercising the actual singleTop SEND delivery.
            app.runOnUiThread { receiver.intent = launchIntent }
        }
    }

    private fun assertSystemBarAppearance(light: Boolean) {
        app.runOnIdle {
            val window = app.activity.window
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(light, bars.isAppearanceLightStatusBars)
            assertEquals(light, bars.isAppearanceLightNavigationBars)
        }
    }
}
