package app.tingyu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tingyu.model.Track
import coil.compose.AsyncImage
import java.util.Locale

@Composable
internal fun Cover(track: Track?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(if (size > 100.dp) 24.dp else 12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.38f))
        if (!track?.coverUrl.isNullOrBlank()) {
            AsyncImage(model = track?.coverUrl, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun PageTitle(title: String, description: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun StatePanel(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    error: Boolean = false,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp),
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun TrackRow(
    track: Track,
    saved: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    selected: Boolean? = null,
    onSelect: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(role = Role.Button, onClickLabel = "播放 ${track.title}", onClick = onPlay)
            .padding(start = if (selected != null) 0.dp else 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selected != null && onSelect != null) {
                Checkbox(checked = selected, onCheckedChange = { onSelect() },
                    modifier = Modifier.semantics { contentDescription = "选择 ${track.title}" })
            }
            Cover(track, if (selected != null) 44.dp else 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist.ifBlank { "哔哩哔哩" }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(track.durationMs.takeIf { it > 0 }?.let(::formatTime), if (saved) "已下载" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

@Composable
internal fun ActionIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label)
    }
}

internal fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1_048_576 -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.CHINA, "%.1f MB", bytes / 1_048_576.0)
}
