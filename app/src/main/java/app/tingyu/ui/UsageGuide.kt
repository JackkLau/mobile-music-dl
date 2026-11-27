package app.tingyu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun UsageGuide(onDismiss: () -> Unit, onParse: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用说明") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GuideStep(Icons.Rounded.ContentPaste, "从分享链接添加",
                    "在音乐平台点「分享」并复制链接，再回到微乐点「粘贴并解析」。")
                GuideStep(Icons.Rounded.Headphones, "听歌",
                    "哔哩哔哩曲目可点歌名试听。网易云和 QQ 音乐单曲下载后，到「音乐库」播放。")
                GuideStep(Icons.Rounded.Share, "发给微信朋友",
                    "在播放器或音乐库点分享，选择微信，再选好友发送。")
            }
        },
        confirmButton = { TextButton(onClick = onParse) { Text("去解析链接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun GuideStep(icon: ImageVector, title: String, instruction: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(instruction, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
