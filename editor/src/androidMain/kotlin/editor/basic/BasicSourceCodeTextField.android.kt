package editor.basic

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope

internal actual fun Modifier.scrollOnPress(
    coroutineScope: CoroutineScope,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState
): Modifier = this

@Composable
@PublishedApi
internal actual fun Modifier.onPointerOffsetChange(
    onOffsetChange: (IntOffset) -> Unit
): Modifier = this
