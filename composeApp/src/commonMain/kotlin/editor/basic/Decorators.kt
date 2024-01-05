package editor.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.roundToInt


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
    val openedBracketLines = buildSet {
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
