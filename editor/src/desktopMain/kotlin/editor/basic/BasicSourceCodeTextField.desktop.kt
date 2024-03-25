package editor.basic

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.scrollOnPress(
    coroutineScope: CoroutineScope,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState
): Modifier {
    // workaround for COMPOSE-727
    val horizontalScrollPosition = horizontalScrollState.value
    val verticalScrollPosition = verticalScrollState.value
    return onPointerEvent(PointerEventType.Press) {
        coroutineScope.launch {
            repeat(50) {
                horizontalScrollState.scrollTo(horizontalScrollPosition)
                verticalScrollState.scrollTo(verticalScrollPosition)
                delay(1)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@PublishedApi
internal actual fun Modifier.onPointerOffsetChange(
    onOffsetChange: (IntOffset) -> Unit
): Modifier = onPointerEvent(PointerEventType.Move) {
    val newOffset = it.changes.first().position.let { (x, y) -> IntOffset(x.toInt(), y.toInt()) }
    onOffsetChange(newOffset)
}
