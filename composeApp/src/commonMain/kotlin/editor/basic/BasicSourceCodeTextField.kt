package editor.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


@Stable
data class BasicSourceCodeTextFieldState<T : Token>(
    val tokens: List<T> = emptyList(),
    val selection: TextRange = TextRange.Zero,
    val composition: TextRange? = null,
) {
    val annotatedString: AnnotatedString by lazy {
        buildAnnotatedString {
            for (token in tokens) {
                append(token.annotatedString)
            }
        }
    }
    val text by lazy { annotatedString.text }

    val offsets: List<List<Int>> by lazy {
        buildList<MutableList<Int>> {
            add(mutableListOf())
            for ((offset, char) in text.withIndex()) {
                last().add(offset)
                if (char == '\n') add(mutableListOf())
            }
            last().add(text.length)
        }
    }

    val lineOffsets by lazy {
        offsets.map { line ->
            line.indexOfFirst { it in text.indices && !text[it].isWhitespace() }.takeIf { it >= 0 }
        }
    }

    val sourceCodePositions: List<SourceCodePosition> by lazy {
        buildList {
            var line = 0
            var column = 0
            for (char in text) {
                add(SourceCodePosition(line, column))
                if (char == '\n') {
                    line++
                    column = 0
                } else {
                    column++
                }
            }
            add(SourceCodePosition(line, column))
        }
    }

    val tokenOffsets: Map<T, IntRange> by lazy {
        buildMap {
            var offset = 0
            for (token in tokens) {
                val startOffset = offset
                offset += token.text.length
                put(token, startOffset..<offset)
            }
        }
    }

    val tokenPositions: Map<T, Pair<SourceCodePosition, SourceCodePosition>> by lazy {
        tokenOffsets.mapValues { (_, range) -> sourceCodePositions[range.first] to sourceCodePositions[range.last] }
    }

    val tokenLines: Map<T, IntRange> by lazy {
        tokenPositions.mapValues { (_, range) -> range.first.line..range.second.line }
    }
}

typealias Preprocessor = (TextFieldValue) -> TextFieldValue
typealias Tokenizer<Token> = (TextFieldValue) -> BasicSourceCodeTextFieldState<Token>

expect fun Modifier.scrollOnPress(
    coroutineScope: CoroutineScope,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState,
): Modifier

data class EditorOffsets(val top: Int = 0, val bottom: Int = 0, val start: Int = 0, val end: Int = 0)

typealias CharEventHandler = (CharEvent) -> TextFieldValue?

fun combineCharEventHandlers(vararg handlers: CharEventHandler): CharEventHandler =
    { event -> handlers.firstNotNullOfOrNull { it(event) } }

typealias KeyEventHandler = (KeyEvent) -> TextFieldValue?

fun combineKeyEventHandlers(vararg handlers: KeyEventHandler): KeyEventHandler =
    { event -> handlers.firstNotNullOfOrNull { it(event) } }

sealed class CharEvent {
    data class Insert(val char: Char) : CharEvent()
    data object Backspace : CharEvent()
    data object Misc : CharEvent()
}

private fun <T : Token> isBackSpace(oldState: BasicSourceCodeTextFieldState<T>, newState: TextFieldValue): Boolean {
    if (isErasedSelectedContent(oldState, newState)) return true
    if (oldState.selection.collapsed && oldState.selection.start > 0) {
        return isErasedSelectedContent(
            oldState.copy(
                selection = TextRange(
                    oldState.selection.end - 1,
                    oldState.selection.end
                )
            ), newState
        )
    }
    return false
}

private fun <T : Token> isErasedSelectedContent(
    oldState: BasicSourceCodeTextFieldState<T>,
    newState: TextFieldValue
): Boolean {
    if (!newState.selection.collapsed) return false
    if (oldState.selection.collapsed) return false
    if (newState.selection.min != oldState.selection.min) return false
    if (newState.text.length != oldState.text.length - oldState.selection.length) return false
    for (i in 0..<oldState.selection.min) {
        if (newState.text[i] != oldState.text[i]) return false
    }
    for (i in oldState.selection.max..<oldState.text.length) {
        if (newState.text[i - oldState.selection.length] != oldState.text[i]) return false
    }
    fun map(offset: Int) = when {
        offset >= oldState.selection.max -> offset - oldState.selection.length
        offset <= oldState.selection.min -> offset
        else -> newState.selection.min
    }
    return when (val oldComposition = oldState.composition) {
        null -> newState.composition == null
        else -> when (val newComposition = newState.composition) {
            null -> false
            else -> newComposition.start == map(oldComposition.start) && newComposition.end == map(oldComposition.end)
        }
    }
}

private fun <T : Token> isCharInserted(
    oldState: BasicSourceCodeTextFieldState<T>,
    newState: TextFieldValue
): Boolean {
    if (!newState.selection.collapsed) return false
    if (newState.selection.min != oldState.selection.min + 1) return false
    if (newState.text.length != oldState.text.length - oldState.selection.length + 1) return false
    for (i in 0..<oldState.selection.min) {
        if (newState.text[i] != oldState.text[i]) return false
    }
    for (i in oldState.selection.max..<oldState.text.length) {
        if (newState.text[i - oldState.selection.length + 1] != oldState.text[i]) return false
    }
    fun map(offset: Int) = when {
        offset >= oldState.selection.max -> offset - oldState.selection.length + 1
        offset <= oldState.selection.min -> offset
        else -> newState.selection.min
    }
    return when (val oldComposition = oldState.composition) {
        null -> newState.composition == null
        else -> when (val newComposition = newState.composition) {
            null -> false
            else -> newComposition.start == map(oldComposition.start) && newComposition.end == map(oldComposition.end)
        }
    }
}

@Composable
fun <T : Token> BasicSourceCodeTextField(
    state: BasicSourceCodeTextFieldState<T>,
    onStateUpdate: (new: BasicSourceCodeTextFieldState<T>) -> Unit,
    preprocessors: List<Preprocessor> = emptyList(),
    tokenize: Tokenizer<T>,
    additionalInnerComposable: @Composable (BoxWithConstraintsScope.(textLayoutResult: TextLayoutResult?) -> Unit) = { _ -> },
    additionalOuterComposable: @Composable (BoxWithConstraintsScope.(textLayoutResult: TextLayoutResult?) -> Unit) = { _ -> },
    showLineNumbers: Boolean = true,
    textStyle: TextStyle = TextStyle.Default.copy(fontFamily = FontFamily.Monospace),
    horizontalScrollState: ScrollState = rememberScrollState(),
    verticalScrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
    editorOffsetsForPosition: (sourceCodePosition: SourceCodePosition) -> EditorOffsets = { EditorOffsets() },
    manualScrollToPosition: SharedFlow<SourceCodePosition> = remember { MutableSharedFlow() },
    charEventHandler: CharEventHandler = { null },
    keyEventHandler: KeyEventHandler = { null },
) {
    val coroutineScope = rememberCoroutineScope()
    val textSize = measureText(textStyle)
    val textHeightDp = with(LocalDensity.current) { textSize.height.toDp() }
    var textLayout: TextLayoutResult? by remember(state.text) { mutableStateOf(null) }

    BoxWithConstraints(modifier) {
        val editorOuterHeight = maxHeight
        val editorOuterHeightPx = with(LocalDensity.current) { editorOuterHeight.toPx() }

        Row(modifier = Modifier.verticalScroll(verticalScrollState)) {
            AnimatedVisibility(showLineNumbers) {
                Row {
                    Column(horizontalAlignment = Alignment.End) {
                        repeat(state.offsets.size) {
                            BasicText(
                                text = "${it.inc()}",
                                style = textStyle,
                                modifier = Modifier.height(textHeightDp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }


            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val editorOuterWidth = maxWidth
                val editorOuterWidthPx = with(LocalDensity.current) { editorOuterWidth.toPx() }

                fun isCursorVisibleShortcut(): Boolean {
                    val position = state.sourceCodePositions[state.selection.end]
                    return isCursorVisible(
                        textSize = textSize,
                        position = position,
                        verticalScrollState = verticalScrollState,
                        horizontalScrollState = horizontalScrollState,
                        offsets = editorOffsetsForPosition(position),
                        editorHeightPx = editorOuterHeightPx,
                        editorWidthPx = editorOuterWidthPx,
                    )
                }

                var shouldCursorBeVisible by remember { mutableStateOf(isCursorVisibleShortcut()) }
                if (horizontalScrollState.isScrollInProgress || verticalScrollState.isScrollInProgress) {
                    DisposableEffect(Unit) {
                        onDispose {
                            shouldCursorBeVisible = isCursorVisibleShortcut()
                        }
                    }
                }
                LaunchedEffect(state, textSize) {
                    if (shouldCursorBeVisible) { // when size changes, presence of cursor on the screen should be preserved
                        coroutineScope.launch {
                            val position = state.sourceCodePositions[state.selection.end]
                            scrollTo(
                                textSize = textSize,
                                position = position,
                                horizontalScrollState = horizontalScrollState,
                                verticalScrollState = verticalScrollState,
                                outerEditorWidthPx = editorOuterWidthPx.roundToInt(),
                                outerEditorHeightPx = editorOuterHeightPx.roundToInt(),
                                offsets = editorOffsetsForPosition(position),
                            )
                        }
                    }
                }
                LaunchedEffect(editorOuterHeightPx, editorOuterWidthPx) {
                    if (shouldCursorBeVisible) { // when size changes, presence of cursor on the screen should be preserved
                        coroutineScope.launch {
                            val position = state.sourceCodePositions[state.selection.end]
                            scrollTo(
                                textSize = textSize,
                                position = position,
                                horizontalScrollState = horizontalScrollState,
                                verticalScrollState = verticalScrollState,
                                outerEditorWidthPx = editorOuterWidthPx.roundToInt(),
                                outerEditorHeightPx = editorOuterHeightPx.roundToInt(),
                                animationSpec = SpringSpec(stiffness = Spring.StiffnessHigh),
                                offsets = editorOffsetsForPosition(position),
                            )
                        }
                    }
                }
                LaunchedEffect(editorOuterHeightPx, editorOuterWidthPx, state, textSize) {
                    manualScrollToPosition.collect {
                        coroutineScope.launch {
                            scrollTo(
                                textSize = textSize,
                                position = it,
                                horizontalScrollState = horizontalScrollState,
                                verticalScrollState = verticalScrollState,
                                outerEditorWidthPx = editorOuterWidthPx.roundToInt(),
                                outerEditorHeightPx = editorOuterHeightPx.roundToInt(),
                                offsets = editorOffsetsForPosition(it),
                            )
                        }
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .horizontalScroll(horizontalScrollState)

                ) {
                    val innerSizes = this
                    var textFieldSize: IntSize? by remember { mutableStateOf(null) }

                    fun onValueChange(newTextFieldState: TextFieldValue) {
                        val preprocessed =
                            preprocessors.fold(newTextFieldState) { acc, preprocessor -> preprocessor(acc) }
                        val charEvent = when {
                            isBackSpace(state, newTextFieldState) -> CharEvent.Backspace
                            isCharInserted(
                                state,
                                newTextFieldState
                            ) -> CharEvent.Insert(char = newTextFieldState.text[newTextFieldState.selection.start - 1])

                            else -> CharEvent.Misc
                        }
                        val handledAsCharEvent = charEventHandler(charEvent) ?: preprocessed
                        val newState = tokenize(handledAsCharEvent)
                        onStateUpdate(newState)
                        shouldCursorBeVisible = true
                    }

                    BasicTextField(
                        value = TextFieldValue(
                            annotatedString = state.annotatedString,
                            selection = state.selection,
                            composition = state.composition,
                        ),
                        onValueChange = { onValueChange(it) },
                        maxLines = Int.MAX_VALUE,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(Color.Black),
                        onTextLayout = { textLayout = it },
                        modifier = Modifier
                            .widthIn(min = editorOuterWidth)
                            .scrollOnPress(coroutineScope, verticalScrollState, horizontalScrollState)
                            .onSizeChanged { textFieldSize = it }
                            .onPreviewKeyEvent {
                                when (val eventResult = keyEventHandler(it)) {
                                    null -> false
                                    else -> {
                                        onValueChange(eventResult)
                                        true
                                    }
                                }
                            },
                    )
                    innerSizes.additionalInnerComposable(textLayout)
                }
            }
        }
        additionalOuterComposable(textLayout)
    }
}

@Composable
fun measureText(codeTextStyle: TextStyle): Size {
    val textMeasurer = rememberTextMeasurer()
    val textLayoutResultSingleLine: TextLayoutResult = textMeasurer.measure(
        text = AnnotatedString("a"),
        style = codeTextStyle,
    )
    val textLayoutResultMultiLine: TextLayoutResult = textMeasurer.measure(
        text = AnnotatedString("a\na"),
        style = codeTextStyle,
    )
    // workaround on Android: COMPOSE-728
    val width = textLayoutResultSingleLine.getLineRight(0) - textLayoutResultSingleLine.getLineLeft(0)
    val height2 = textLayoutResultMultiLine.getLineBottom(1) - textLayoutResultMultiLine.getLineTop(0)
    val height1 = textLayoutResultSingleLine.getLineBottom(0) - textLayoutResultSingleLine.getLineTop(0)
    return Size(width, height2 - height1)
}

private suspend fun scrollTo(
    textSize: Size,
    position: SourceCodePosition,
    horizontalScrollState: ScrollState,
    verticalScrollState: ScrollState,
    outerEditorWidthPx: Int,
    outerEditorHeightPx: Int,
    animationSpec: AnimationSpec<Float>? = SpringSpec(),
    offsets: EditorOffsets = EditorOffsets(),
) = coroutineScope {
    val scroll: suspend ScrollState.(Int) -> Unit =
        if (animationSpec == null) ScrollState::scrollTo else {
            { animateScrollTo(it, animationSpec) }
        }
    launch {
        for (n in 5 downTo 0) {
            val linesToShowBefore = n
            val linesToShowAfter = n
            val lineHeight = textSize.height
            val expectedLinePosition = position.line * lineHeight
            val needToMoveAbove =
                verticalScrollState.value >= expectedLinePosition - lineHeight * linesToShowBefore - offsets.top
            val needToMoveBelow =
                verticalScrollState.value <= expectedLinePosition - outerEditorHeightPx + lineHeight * (1 + linesToShowAfter) + offsets.bottom
            if (needToMoveAbove && needToMoveBelow) {
                continue
            } else if (needToMoveAbove) {
                verticalScrollState.scroll((expectedLinePosition - lineHeight * linesToShowBefore - offsets.top).roundToInt())
            } else if (needToMoveBelow) {
                verticalScrollState.scroll((expectedLinePosition - outerEditorHeightPx + lineHeight * (1 + linesToShowAfter) + offsets.bottom).roundToInt())
            }
            break
        }
    }
    launch {
        for (n in 5 downTo 0) {
            val lineWidth = textSize.width
            val charsToShowBefore = n
            val charsToShowAfter = n
            val expectedCharacterPosition = lineWidth * position.column
            val needToMoveLeft =
                horizontalScrollState.value >= expectedCharacterPosition - lineWidth * charsToShowBefore - offsets.start
            val needToMoveRight =
                horizontalScrollState.value <= expectedCharacterPosition - outerEditorWidthPx + lineWidth * charsToShowAfter + offsets.end
            if (needToMoveLeft && needToMoveRight) {
                continue
            } else if (needToMoveLeft) {
                horizontalScrollState.scroll((expectedCharacterPosition - lineWidth * charsToShowBefore - offsets.start).roundToInt())
            } else if (needToMoveRight) {
                horizontalScrollState.scroll((expectedCharacterPosition - outerEditorWidthPx + lineWidth * charsToShowAfter + offsets.end).roundToInt())
            }
            break
        }
    }
}

private fun isCursorVisible(
    textSize: Size,
    position: SourceCodePosition,
    verticalScrollState: ScrollState,
    editorHeightPx: Float,
    horizontalScrollState: ScrollState,
    editorWidthPx: Float,
    offsets: EditorOffsets = EditorOffsets(),
): Boolean {

    val lineHeight = textSize.height
    val expectedLinePosition = position.line * lineHeight
    if (verticalScrollState.value > expectedLinePosition - offsets.top) return false
    if (verticalScrollState.value < expectedLinePosition - editorHeightPx + offsets.bottom) return false

    val lineWidth = textSize.width
    val expectedCharacterPosition = lineWidth * position.column
    if (horizontalScrollState.value > expectedCharacterPosition - offsets.start) return false
    if (horizontalScrollState.value < expectedCharacterPosition - editorWidthPx + offsets.end) return false

    return true
}

data class SourceCodePosition(val line: Int, val column: Int)
