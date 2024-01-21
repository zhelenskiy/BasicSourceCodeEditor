package editor.basic

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope

actual fun Modifier.scrollOnPress(
    coroutineScope: CoroutineScope,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState
): Modifier = this

@Composable
actual fun Modifier.tooltip(
    onOffsetChange: (IntOffset) -> Unit
): Modifier = this
