package app.tingyu.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.tingyu.MainActivity
import org.junit.Rule
import org.junit.Test

class PlaybackNavigationTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun incomingShareOpensParserFromPlaybackHome() {
        app.onNodeWithText("播放").assertIsSelected()
        val shared = "【黑胶试听合輯】周杰伦_葉惠美 https://bili2233.cn/SrmWssU"
        app.runOnUiThread {
            app.activity.startActivity(Intent(app.activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shared)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        app.waitUntil(5000) { app.onAllNodes(androidx.compose.ui.test.hasText(shared)).fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText("解析").assertIsSelected()
        app.onNodeWithText(shared).assertExists()
        app.onNodeWithText("解析链接").assertIsEnabled()
    }
}
