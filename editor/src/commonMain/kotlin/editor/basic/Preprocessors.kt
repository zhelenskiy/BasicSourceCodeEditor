package editor.basic

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue


public fun replaceTabs(textFieldValue: TextFieldValue, spaces: Int = 4): TextFieldValue {
    var selectionStartOffset = 0
    var selectionEndOffset = 0
    var compositionStartOffset = 0
    var compositionEndOffset = 0
    for ((i, c) in textFieldValue.text.withIndex()) {
        if (c == '\t') {
            if (i < textFieldValue.selection.start) selectionStartOffset += spaces - 1
            if (i <= textFieldValue.selection.end) selectionEndOffset += spaces - 1
            textFieldValue.composition?.let {
                if (i < it.start) compositionStartOffset += spaces - 1
                if (i <= it.end) compositionEndOffset += spaces - 1
            }
        }
    }
    return TextFieldValue(
        text = textFieldValue.text.replace("\t", " ".repeat(spaces)),
        selection = TextRange(
            textFieldValue.selection.start + selectionStartOffset,
            textFieldValue.selection.end + selectionEndOffset
        ),
        composition = textFieldValue.composition?.let {
            TextRange(
                it.start + compositionStartOffset,
                it.end + compositionEndOffset
            )
        }
    )
}
