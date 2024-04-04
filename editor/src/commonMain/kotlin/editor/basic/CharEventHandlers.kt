package editor.basic

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue


public fun <T : Token> openingBracketCharEventHandler(
    textFieldState: BasicSourceCodeTextFieldState<T>,
    openingChar: Char, openingBracket: String, closingBracket: String,
    addNewLinesForSelection: (CharEvent.Insert) -> Boolean = { false },
    indent: String? = " ".repeat(4),
): CharEventHandler = f@{ keyEvent ->
    if (keyEvent !is CharEvent.Insert || keyEvent.char != openingChar) return@f null
    val oldSelection = textFieldState.selection
    val minLine = textFieldState.sourceCodePositions[oldSelection.min].line
    val maxLine = textFieldState.sourceCodePositions[oldSelection.max].line
    var newSelectionStart = oldSelection.start
    var newSelectionEnd = oldSelection.end
    val oldComposition = textFieldState.composition
    var newCompositionStart = oldComposition?.start
    var newCompositionEnd = oldComposition?.end
    val addNewLinesForSelection = addNewLinesForSelection(keyEvent)
    val (openingBracket, closingBracket) = if (addNewLinesForSelection && !oldSelection.collapsed) {
        val minLineOffset = textFieldState.offsets[minLine].first()
        val offset =
            textFieldState.lineOffsets[minLine]?.let { it + minLineOffset } ?: textFieldState.offsets[minLine].last()
        val newOpeningBracket =
            "$openingBracket\n${textFieldState.text.substring(minLineOffset, offset)}${indent ?: ""}"
        val newClosingBracket =
            "\n${textFieldState.text.substring(minLineOffset, offset)}$closingBracket"
        newOpeningBracket to newClosingBracket
    } else {
        openingBracket to closingBracket
    }
    val newString = buildString {
        appendRange(textFieldState.text, 0, oldSelection.min)
        fun increment(length: Int, oldOffset: Int) {
            if (oldSelection.start >= oldOffset) newSelectionStart += length
            if (oldSelection.end >= oldOffset) newSelectionEnd += length
            if (oldComposition != null) {
                if (oldComposition.start >= oldOffset) newCompositionStart =
                    newCompositionStart!! + length
                if (oldComposition.end >= oldOffset) newCompositionEnd =
                    newCompositionEnd!! + length
            }
        }

        fun addIndentedLine(lineNumber: Int) {
            val lineStart = textFieldState.offsets[lineNumber].first()
            val lineEnd = minOf(textFieldState.offsets[lineNumber].last(), oldSelection.max)
            if (indent == null) {
                appendRange(textFieldState.text, lineStart, lineEnd)
                return
            }
            val offset = textFieldState.lineOffsets[lineNumber]?.let { lineStart + it } ?: lineEnd

            appendRange(textFieldState.text, lineStart, offset)

            append(indent)
            increment(indent.length, offset)

            appendRange(textFieldState.text, offset, lineEnd)
        }
        append(openingBracket)
        increment(openingBracket.length, oldSelection.min)

        if (!oldSelection.collapsed) {
            if (minLine == maxLine || indent == null) {
                appendRange(textFieldState.text, oldSelection.min, oldSelection.max)
            } else {
                val firstLineEndOffset = textFieldState.offsets[minLine].last()

                appendRange(textFieldState.text, oldSelection.min, firstLineEndOffset)

                append('\n')

                for (line in (minLine + 1)..maxLine) {
                    addIndentedLine(line)
                    if (line < maxLine) {
                        append('\n')
                    }
                }
            }
        }
        append(closingBracket)
        increment(closingBracket.length, oldSelection.max + 1)

        appendRange(textFieldState.text, oldSelection.max, textFieldState.text.length)
    }
    val newSelection = TextRange(newSelectionStart, newSelectionEnd)
    val newComposition =
        if (newCompositionStart == null || newCompositionEnd == null) null
        else TextRange(newCompositionStart!!, newCompositionEnd!!)
    TextFieldValue(newString, newSelection, newComposition)
}

public inline fun <T : Token, reified Bracket : ScopeChangingToken> closingBracketCharEventHandler(
    textFieldState: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    openingBracket: String, closingChar: Char, closingBracket: String,
): CharEventHandler = f@{ keyEvent ->
    if (keyEvent !is CharEvent.Insert || keyEvent.char != closingChar) return@f null

    val position = textFieldState.sourceCodePositions[textFieldState.selection.min]
    val finishLine = position.line
    val oldOffsetStart = textFieldState.offsets[finishLine].first()
    val oldOffsetFinish =
        textFieldState.lineOffsets[finishLine]?.let { it + oldOffsetStart } ?: textFieldState.offsets[finishLine].last()
    if (position.column > (textFieldState.lineOffsets[finishLine] ?: Int.MAX_VALUE)) return@f null
    val token = textFieldState.tokens
        .lastOrNull {
            it is Bracket && textFieldState.tokenOffsets[it]!!.last < textFieldState.selection.min &&
            it.scopeChange == ScopeChange.OpensScope && it.text == openingBracket &&
            matchedBrackets[it].let { paired ->
                paired == null || textFieldState.tokenOffsets[paired as T]!!.first >= textFieldState.selection.max
            }
        }
        ?: return@f null

    val openTokenStartPosition = textFieldState.tokenPositions[token]?.first ?: return@f null
    val startLine = openTokenStartPosition.line
    val newOffsetStart = textFieldState.offsets[startLine].first()
    val newOffsetFinish = textFieldState.lineOffsets[startLine]?.let { it + textFieldState.offsets[startLine].first() }
        ?: textFieldState.offsets[startLine].last()
    val lengthDiff = (newOffsetFinish - newOffsetStart) - (oldOffsetFinish - oldOffsetStart)
    val newText = buildString {
        appendRange(textFieldState.text, 0, oldOffsetStart)
        appendRange(textFieldState.text, newOffsetStart, newOffsetFinish)
        append(closingBracket)
        appendRange(textFieldState.text, oldOffsetFinish, textFieldState.text.length)
    }
    val remapOldOffset = { oldOffset: Int ->
        when {
            oldOffset >= oldOffsetFinish -> oldOffset + lengthDiff + closingBracket.length
            oldOffset <= oldOffsetStart -> oldOffset
            else -> oldOffset.coerceIn(newOffsetStart, newOffsetFinish)
        }
    }

    TextFieldValue(
        text = newText,
        selection = textFieldState.selection.let { TextRange(remapOldOffset(it.start), remapOldOffset(it.end)) },
        composition = textFieldState.composition?.let {
            TextRange(
                remapOldOffset(it.start),
                remapOldOffset(it.end)
            )
        }
    )
}

public fun <T : Token> reusingCharsEventHandler(
    textFieldState: BasicSourceCodeTextFieldState<T>,
    chars: String,
): CharEventHandler = f@{ keyEvent ->
    if (
        keyEvent !is CharEvent.Insert || keyEvent.char !in chars || !textFieldState.selection.collapsed ||
        textFieldState.selection.start == textFieldState.text.length ||
        textFieldState.text[textFieldState.selection.start] != keyEvent.char
    ) return@f null
    TextFieldValue(
        text = textFieldState.text,
        selection = textFieldState.selection.let { TextRange(it.start + 1, it.end + 1) },
        composition = textFieldState.composition
    )
}

public inline fun <reified T : Token, reified Bracket : ScopeChangingToken> newLineCharEventHandler(
    textFieldState: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    indent: String = " ".repeat(4),
): CharEventHandler = f@{ keyEvent ->
    if (keyEvent !is CharEvent.Insert || keyEvent.char != '\n') return@f null
    val currentLine = textFieldState.sourceCodePositions[textFieldState.selection.min].line
    val newIndents = textFieldState.tokens.filter {
        if (it !is Bracket) return@filter false
        if (it.scopeChange != ScopeChange.OpensScope) return@filter false
        val match = matchedBrackets[it]
        if (match != null) {
            val matchStartOffset = textFieldState.tokenOffsets[match as T]?.first ?: return@filter false
            if (matchStartOffset < textFieldState.selection.min) return@filter false
        }
        textFieldState.tokenLines[it]?.last == currentLine &&
                textFieldState.tokenOffsets[it].let { it != null && it.last <= textFieldState.selection.min }
    }
    var diffLength = 0
    val newText = buildString {
        appendRange(textFieldState.text, 0, textFieldState.selection.min)
        append('\n')
        diffLength += 1
        val currentLineStartOffset = textFieldState.offsets[currentLine].first()
        val currentLineEndOffset = textFieldState.lineOffsets[currentLine]?.let { it + currentLineStartOffset }
            ?: textFieldState.offsets[currentLine].last()
        diffLength += currentLineEndOffset - currentLineStartOffset
        appendRange(textFieldState.text, currentLineStartOffset, currentLineEndOffset)
        repeat(newIndents.size) {
            append(indent)
            diffLength += indent.length
        }

        val firstNotSpace = (textFieldState.selection.max..<textFieldState.text.length)
            .firstOrNull { !textFieldState.text[it].isWhitespace() }
        if (
            firstNotSpace != null && newIndents.any {
                val match = matchedBrackets[it as Bracket] ?: return@any false
                val range = textFieldState.tokenOffsets[match as T] ?: return@any false
                val tokenLines = textFieldState.tokenLines[match] ?: return@any false
                firstNotSpace in range && currentLine in tokenLines
            }
        ) {
            append('\n')
            appendRange(textFieldState.text, currentLineStartOffset, currentLineEndOffset)
        }
        appendRange(textFieldState.text, textFieldState.selection.max, textFieldState.text.length)
    }

    val remapOldOffset = { oldOffset: Int ->
        when {
            oldOffset >= textFieldState.selection.max -> oldOffset + diffLength - textFieldState.selection.length
            oldOffset <= textFieldState.selection.min -> oldOffset
            else -> oldOffset.coerceIn(textFieldState.selection.min, textFieldState.selection.min + diffLength)
        }
    }

    TextFieldValue(
        text = newText,
        selection = textFieldState.selection.let { TextRange(remapOldOffset(it.start), remapOldOffset(it.end)) },
        composition = textFieldState.composition?.let {
            TextRange(remapOldOffset(it.start), remapOldOffset(it.end))
        }
    )
}

public fun <T : Token> removeIndentBackspaceCharEventHandler(
    textFieldState: BasicSourceCodeTextFieldState<T>,
    indent: String = " ".repeat(4),
): CharEventHandler = f@{ keyEvent ->
    val offset = textFieldState.selection.start
    if (keyEvent !is CharEvent.Backspace || !textFieldState.selection.collapsed || offset < indent.length) return@f null
    val position = textFieldState.sourceCodePositions[offset]
    val line = position.line
    val lineOffset = textFieldState.lineOffsets[line]
    if (lineOffset != null && lineOffset < position.column) return@f null
    for ((i, c) in indent.withIndex()) {
        if (textFieldState.text[offset - indent.length + i] != c) return@f null
    }
    val newText = textFieldState.text.removeRange(offset - indent.length, offset)
    fun remap(oldOffset: Int): Int = when {
        oldOffset >= offset -> oldOffset - indent.length
        oldOffset <= offset - indent.length -> oldOffset
        else -> offset - indent.length
    }
    TextFieldValue(
        text = newText,
        selection = TextRange(offset - indent.length),
        composition = textFieldState.composition?.let { TextRange(remap(it.start), remap(it.end)) }
    )
}

public fun <T : Token> removeEmptyBracesBackspaceCharEventHandler(
    textFieldState: BasicSourceCodeTextFieldState<T>,
    openingBracket: String,
    closingBracket: String,
): CharEventHandler = f@{ keyEvent ->
    val offset = textFieldState.selection.start
    if (
        keyEvent !is CharEvent.Backspace || !textFieldState.selection.collapsed || offset < openingBracket.length ||
        offset + closingBracket.length > textFieldState.text.length
    ) return@f null
    for ((i, c) in openingBracket.withIndex()) {
        if (textFieldState.text[offset - openingBracket.length + i] != c) return@f null
    }
    for ((i, c) in closingBracket.withIndex()) {
        if (textFieldState.text[offset + i] != c) return@f null
    }
    val newText = textFieldState.text.removeRange(offset - openingBracket.length, offset + closingBracket.length)
    val bothBracketsLength = openingBracket.length + closingBracket.length
    fun remap(oldOffset: Int): Int = when {
        oldOffset >= offset + closingBracket.length -> oldOffset - bothBracketsLength
        oldOffset <= offset - openingBracket.length -> oldOffset
        else -> offset - openingBracket.length
    }
    TextFieldValue(
        text = newText,
        selection = TextRange(offset - openingBracket.length),
        composition = textFieldState.composition?.let { TextRange(remap(it.start), remap(it.end)) }
    )
}
