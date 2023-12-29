package editor.basic

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.scrollOnPress(
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