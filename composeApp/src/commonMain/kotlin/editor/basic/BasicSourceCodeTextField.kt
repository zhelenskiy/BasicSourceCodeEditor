package editor.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
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

sealed class SuspendResult<out T> {
    data object NotStarted : SuspendResult<Nothing>()
    data object InProgress : SuspendResult<Nothing>()
    data class Success<out T>(val value: T) : SuspendResult<T>()
    data class Failure(val throwable: Throwable) : SuspendResult<Nothing>()
}

val <T : Any> SuspendResult<T>.result
    get() = when (this) {
        is SuspendResult.Success -> value
        SuspendResult.NotStarted, SuspendResult.InProgress, is SuspendResult.Failure -> null
    }

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

sealed class FormattedCode<Token> {
    abstract val selection: TextRange
    abstract val composition: TextRange?

    data class AsTokens<Token>(
        val tokens: List<Token>,
        override val selection: TextRange = TextRange.Zero,
        override val composition: TextRange? = null,
    ) : FormattedCode<Token>()

    data class AsString<Token>(
        val code: String,
        override val selection: TextRange = TextRange.Zero,
        override val composition: TextRange? = null,
    ) : FormattedCode<Token>()
}

typealias Preprocessor = (TextFieldValue) -> TextFieldValue
typealias Tokenizer<Token> = (TextFieldValue) -> BasicSourceCodeTextFieldState<Token>

expect fun Modifier.scrollOnPress(
    coroutineScope: CoroutineScope,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState,
): Modifier

data class EditorOffsets(val top: Int = 0, val bottom: Int = 0, val start: Int = 0, val end: Int = 0)

@Composable
fun <T : Token> BasicSourceCodeTextField(
    state: BasicSourceCodeTextFieldState<T>,
    onStateUpdate: (new: BasicSourceCodeTextFieldState<T>) -> Unit,
    preprocessors: List<Preprocessor> = emptyList(),
    tokenize: Tokenizer<T>,
    additionalInnerComposable: @Composable (BoxWithConstraintsScope.(textLayoutResult: TextLayoutResult?) -> Unit) = { _ -> },
    additionalOuterComposable: @Composable (BoxWithConstraintsScope.(textLayoutResult: TextLayoutResult?) -> Unit) = { _ -> },
    format: suspend (List<T>) -> FormattedCode<T> = { FormattedCode.AsTokens(it) },
    showLineNumbers: Boolean = true,
    textStyle: TextStyle = TextStyle.Default.copy(fontFamily = FontFamily.Monospace),
    horizontalScrollState: ScrollState = rememberScrollState(),
    verticalScrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
    editorOffsetsForPosition: (sourceCodePosition: SourceCodePosition) -> EditorOffsets = { EditorOffsets() },
    manualScrollToPosition: SharedFlow<SourceCodePosition> = remember { MutableSharedFlow() }
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

                BoxWithConstraints(Modifier.horizontalScroll(horizontalScrollState)) {
                    val innerSizes = this
                    var textFieldSize: IntSize? by remember { mutableStateOf(null) }

                    BasicTextField(
                        value = TextFieldValue(
                            annotatedString = state.annotatedString,
                            selection = state.selection,
                            composition = state.composition,
                        ),
                        onValueChange = {
                            val newState = tokenize(preprocessors.fold(it) { acc, preprocessor -> preprocessor(acc) })
                            val position = newState.sourceCodePositions[newState.selection.end]
                            onStateUpdate(newState)
                            shouldCursorBeVisible = true
                        },
                        maxLines = Int.MAX_VALUE,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(Color.Black),
                        onTextLayout = { textLayout = it },
                        modifier = Modifier
                            .widthIn(min = editorOuterWidth)
                            .scrollOnPress(coroutineScope, verticalScrollState, horizontalScrollState)
                            .onSizeChanged { textFieldSize = it },
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
        if (animationSpec == null) ScrollState::scrollTo else { { animateScrollTo(it, animationSpec) }}
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

fun <Bracket : ScopeChangingToken, T : Token> getIndentationLines(
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    distinct: Boolean = false,
): List<SourceCodePosition> = buildList {
    for ((opening, closing) in matchedBrackets) {
        if (opening.scopeChange != ScopeChange.OpensScope) continue
        val openingLines = state.tokenLines[opening as T] ?: continue
        val closingLines = state.tokenLines[closing as T] ?: continue
        val originalRange = (openingLines.last + 1)..closingLines.first
        val columnOffset =
            (openingLines.first..openingLines.last).mapNotNull { state.lineOffsets[it] }.minOrNull() ?: continue
        for (line in originalRange) {
            val offset = state.lineOffsets[line]
            if (offset == null || offset > columnOffset) {
                add(SourceCodePosition(line, columnOffset))
            }
        }
    }
}.let { if (distinct) it.distinct() else it }


@Composable
fun BoxScope.IndentationLines(
    indentationLines: List<SourceCodePosition>,
    modifier: Modifier,
    width: Dp = 1.dp,
    textStyle: TextStyle,
    mapLineNumbers: (Int) -> Int? = { it },
) {
    val measuredText = measureText(textStyle)
    val letterHeight = measuredText.height
    val letterWidth = measuredText.width

    for ((line, column) in indentationLines) {
        val realLine = mapLineNumbers(line) ?: continue
        val bottom = letterHeight * realLine.inc()
        val end = letterWidth * column
        with(LocalDensity.current) {
            Box(Modifier.size(end.toDp() + width / 2, bottom.toDp()), Alignment.BottomEnd) {
                Spacer(
                    modifier = modifier
                        .width(width)
                        .height(letterHeight.toDp())
                )
            }
        }
    }
}

inline fun <reified Bracket : ScopeChangingToken, T : Token> getPinnedLines(
    line: Int,
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    crossinline pinLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] },
): Set<Int> {
    val lineUsages = IntArray(state.offsets.size)
    var topLine = line
    var tokenIndex = 0
    val openedBracketLines = buildSet<Bracket> {
        while (true) {
            val oldSize = size
            val lastAdded = mutableSetOf<Bracket>()
            val lastRemoved = mutableSetOf<Bracket>()
            while (tokenIndex < state.tokens.size) {
                val token = state.tokens[tokenIndex]
                if ((state.tokenPositions[token] ?: continue).second.line >= topLine) break
                if (token is Bracket) {
                    val (start, end) = state.tokenPositions[token] ?: continue
                    when (token.scopeChange) {
                        ScopeChange.OpensScope -> {
                            add(token)
                            lastAdded.add(token)
                            for (i in start.line..end.line) {
                                lineUsages[i]++
                            }
                        }

                        ScopeChange.ClosesScope -> {
                            val openingBracket = matchedBrackets[token]
                            if (openingBracket != null && remove(openingBracket)) {
                                lastRemoved.add(openingBracket)
                                for (i in start.line..end.line) {
                                    lineUsages[i]--
                                }
                            }
                        }
                    }
                }
                tokenIndex++
            }
            val newSize = size
            val diff = newSize - oldSize
            if (diff <= 0) {
                lastAdded.forEach { remove(it) }
                break
            }
            topLine += diff
        }
    }
    return openedBracketLines.flatMapTo(mutableSetOf()) { bracket ->
        pinLinesChooser(bracket) ?: IntRange.EMPTY
    }.sorted().toSet()
}

inline fun <reified Bracket : ScopeChangingToken, T : Token> getOffsetForLineToAppearOnTop(
    line: Int,
    textSize: Size,
    density: Density,
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    dividerThickness: Dp,
    maximumPinnedLinesHeight: Dp,
    crossinline pinLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] }
): Int {
    val resultLine = (line downTo 0).firstOrNull { attemptLine ->
        val height = getPinnedLinesHeight(
            attemptLine,
            textSize,
            density,
            state,
            matchedBrackets,
            dividerThickness,
            maximumPinnedLinesHeight,
            pinLinesChooser
        )
        (line - attemptLine) * textSize.height >= height
    } ?: 0
    return ((line - resultLine) * textSize.height).roundToInt()
}


inline fun <reified Bracket : ScopeChangingToken, T : Token> getPinnedLinesHeight(
    line: Int,
    textSize: Size,
    density: Density,
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    dividerThickness: Dp,
    maximumPinnedLinesHeight: Dp,
    crossinline pinLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] }
): Int {
    val pinnedLines = getPinnedLines<Bracket, T>(line, state, matchedBrackets, pinLinesChooser)
    if (pinnedLines.isEmpty()) return 0
    return with(density) {
        minOf(pinnedLines.size * textSize.height + dividerThickness.toPx(), maximumPinnedLinesHeight.toPx())
    }.roundToInt()
}

@Composable
inline fun <reified Bracket : ScopeChangingToken, T : Token> BoxWithConstraintsScope.PinnedLines(
    state: BasicSourceCodeTextFieldState<T>,
    textStyle: TextStyle,
    scrollState: ScrollState,
    showLineNumbers: Boolean,
    matchedBrackets: Map<Bracket, Bracket>,
    dividerThickness: Dp = 1.dp,
    maximumPinnedLinesHeight: Dp = maxHeight / 3,
    crossinline pinLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] },
    crossinline onClick: (lineNumber: Int) -> Unit = {},
    crossinline additionalInnerComposable: @Composable BoxWithConstraintsScope.(linesToWrite: Map<Int, AnnotatedString>) -> Unit = { },
) {
    val measuredText = measureText(textStyle)
    val textHeightDp = with(LocalDensity.current) { measuredText.height.toDp() }
    val topVisibleRow = (scrollState.value / measuredText.height).toInt()
    val requestedLinesSet = getPinnedLines(topVisibleRow, state, matchedBrackets, pinLinesChooser)
    if (requestedLinesSet.isEmpty()) return
    Column(Modifier.heightIn(max = maximumPinnedLinesHeight)) {
        Row(
            modifier = Modifier
                .width(this@PinnedLines.maxWidth)
                .verticalScroll(rememberScrollState())
                .background(Color.White)
        ) {
            val lineCount: Int = state.offsets.size
            val linesToWrite = requestedLinesSet.associateWith { lineNumber ->
                val lineOffsets =
                    state.offsets[lineNumber].takeIf { it.isNotEmpty() } ?: return@associateWith AnnotatedString("")
                val lastOffset =
                    if (lineOffsets.last() == state.text.lastIndex) state.text.length else lineOffsets.last()
                buildAnnotatedString {
                    append(state.annotatedString, lineOffsets.first(), lastOffset)
                }
            }
            AnimatedVisibility(showLineNumbers) {
                Column {
                    for ((lineNumber, _) in linesToWrite) {
                        Row(modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onClick(lineNumber) }
                            )
                        ) {
                            val lineNumbersWidth = with(LocalDensity.current) {
                                (lineCount.toString().length * measuredText.width).toDp()
                            }
                            Box(Modifier.width(lineNumbersWidth)) {
                                BasicText(
                                    text = "${lineNumber.inc()}",
                                    style = textStyle,
                                    modifier = Modifier.align(Alignment.CenterEnd).height(textHeightDp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
            BoxWithConstraints {
                val outerScope = this
                BoxWithConstraints(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    val innerScope = this
                    var maxWidth by remember { mutableStateOf(outerScope.maxWidth) }
                    Column {
                        for ((lineNumber, annotatedString) in linesToWrite) {
                            Box(
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onClick(lineNumber) }
                                    )
                                    .fillMaxWidth()
                            ) {
                                BasicText(
                                    text = annotatedString,
                                    style = textStyle,
                                    modifier = Modifier
                                        .height(textHeightDp)
                                        .widthIn(min = maxWidth)
                                        .layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            maxWidth = max(maxWidth, placeable.width.toDp())

                                            layout(placeable.width, placeable.height) {
                                                placeable.placeRelative(0, 0)
                                            }
                                        },
                                )
                            }
                        }
                    }
                    innerScope.additionalInnerComposable(linesToWrite)
                }
            }
        }
        Divider(thickness = dividerThickness)
    }
}
