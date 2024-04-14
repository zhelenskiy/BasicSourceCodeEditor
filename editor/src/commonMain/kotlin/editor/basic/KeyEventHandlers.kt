package editor.basic

import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.jvm.JvmName

public typealias PhysicalKeyboardEventHandler = (PhysicalKeyboardEvent) -> TextFieldValue?
public typealias PhysicalKeyboardEventFilter = (PhysicalKeyboardEvent) -> Boolean

public fun PhysicalKeyboardEventHandler.asKeyboardEventHandler(): KeyboardEventHandler = {
    if (it is PhysicalKeyboardEvent) invoke(it) else null
}

public fun PhysicalKeyboardEventFilter.asKeyboardEventFilter(): KeyboardEventFilter = {
    it is PhysicalKeyboardEvent && invoke(it)
}

public fun <T : Token> handleMovingOffsets(
    state: BasicSourceCodeTextFieldState<T>,
    indent: String = " ".repeat(4),
    moveForwardFilter: KeyboardEventFilter = { keyEvent: PhysicalKeyboardEvent ->
        !keyEvent.isShiftPressed && keyEvent.key == Key.Tab && keyEvent.type == KeyEventType.KeyDown &&
                !keyEvent.isAltPressed && !keyEvent.isCtrlPressed && !keyEvent.isMetaPressed
    }.asKeyboardEventFilter(),
    moveBackwardFilter: KeyboardEventFilter = { keyEvent: PhysicalKeyboardEvent ->
        keyEvent.isShiftPressed && keyEvent.key == Key.Tab && keyEvent.type == KeyEventType.KeyDown &&
                !keyEvent.isAltPressed && !keyEvent.isCtrlPressed && !keyEvent.isMetaPressed
    }.asKeyboardEventFilter(),
): KeyboardEventHandler = f@{ keyboardEvent: KeyboardEvent ->
    val moveForward = !state.selection.collapsed && moveForwardFilter(keyboardEvent)
    val moveBackward = moveBackwardFilter(keyboardEvent)
    if (moveBackward == moveForward) return@f null
    val selectionLines =
        state.sourceCodePositions[state.selection.min].line..state.sourceCodePositions[state.selection.max].line
    val offsetBefore = state.offsets.getOrNull(selectionLines.first - 1)?.last()
    val offsetAfter = state.offsets.getOrNull(selectionLines.last + 1)?.first()
    var newSelectionStart = state.selection.start
    var newSelectionEnd = state.selection.end
    var newCompositionStart = state.composition?.start
    var newCompositionEnd = state.composition?.end
    if (moveBackward) {
        fun increment(length: Int, oldOffsetStart: Int) {
            if (state.selection.start >= oldOffsetStart)
                newSelectionStart -= minOf(length, state.selection.start - oldOffsetStart)

            if (state.selection.end >= oldOffsetStart)
                newSelectionEnd -= minOf(length, state.selection.end - oldOffsetStart)

            if (state.composition != null) {
                if (state.composition.start >= oldOffsetStart)
                    newCompositionStart =
                        newCompositionStart!! - minOf(length, state.composition.start - oldOffsetStart)

                if (state.composition.end >= oldOffsetStart)
                    newCompositionEnd = newCompositionEnd!! - minOf(length, state.composition.end - oldOffsetStart)
            }
        }

        val newText = buildString {
            offsetBefore?.let {
                appendRange(state.text, 0, it + 1)
            }

            for (line in selectionLines) {
                val start = state.offsets[line].first()
                val end = state.offsets[line].last()
                val offset = state.lineOffsets[line]?.let { it + start } ?: end
                val commonChars = minOf(indent.length, offset - start)
                appendRange(state.text, start, offset - commonChars)
                increment(commonChars, offset - commonChars)
                appendRange(state.text, offset, end)
                if (line != selectionLines.last) {
                    append('\n')
                }
            }

            offsetAfter?.let { appendRange(state.text, it - 1, state.text.length) }
        }
        TextFieldValue(
            text = newText,
            selection = TextRange(newSelectionStart, newSelectionEnd),
            composition = newCompositionStart?.let { TextRange(it, newCompositionEnd!!) },
        )
    } else {
        fun increment(length: Int, oldOffset: Int) {
            if (state.selection.start >= oldOffset) newSelectionStart += length
            if (state.selection.end >= oldOffset) newSelectionEnd += length
            if (state.composition != null) {
                if (state.composition.start >= oldOffset) newCompositionStart =
                    newCompositionStart!! + length
                if (state.composition.end >= oldOffset) newCompositionEnd =
                    newCompositionEnd!! + length
            }

        }

        val newText = buildString {
            offsetBefore?.let {
                appendRange(state.text, 0, it + 1)
            }

            for (line in selectionLines) {
                val start = state.offsets[line].first()
                val end = state.offsets[line].last()
                val offset = state.lineOffsets[line]?.let { it + start } ?: end
                appendRange(state.text, start, offset)
                append(indent)
                increment(indent.length, offset)
                appendRange(state.text, offset, end)
                if (line != selectionLines.last) {
                    append('\n')
                }
            }

            offsetAfter?.let { appendRange(state.text, it - 1, state.text.length) }
        }
        TextFieldValue(
            text = newText,
            selection = TextRange(newSelectionStart, newSelectionEnd),
            composition = newCompositionStart?.let { TextRange(it, newCompositionEnd!!) },
        )
    }
}
