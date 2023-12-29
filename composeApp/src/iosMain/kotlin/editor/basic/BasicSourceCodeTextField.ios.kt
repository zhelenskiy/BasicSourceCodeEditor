package editor.basic

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope

actual fun Modifier.scrollOnPress(
    coroutineScope: CoroutineScope,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState
): Modifier = this