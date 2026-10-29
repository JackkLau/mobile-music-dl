package app.tingyu

import app.tingyu.model.AppUiState
import app.tingyu.model.Track

/** A picker or storage operation can finish after playback has moved to another song. */
internal fun AppUiState.songFailure(target: Track, text: String, lyrics: Boolean = false): AppUiState = copy(
    message = "${target.title}：$text",
    musicError = if (musicEditorVisible && (musicEditorTrack ?: playback.track)?.id == target.id) text else musicError,
    lyricsError = if (lyrics && playback.track?.id == target.id) text else lyricsError,
)
