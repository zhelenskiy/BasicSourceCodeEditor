package editor.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.roundToInt


public fun <Bracket : ScopeChangingToken, T : Token> getIndentationLines(
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
public fun BoxScope.IndentationLines(
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
            Box(Modifier.size(end.toDp() + width, bottom.toDp()), Alignment.BottomEnd) {
                Spacer(
                    modifier = modifier
                        .width(width)
                        .height(letterHeight.toDp())
                )
            }
        }
    }
}

@PublishedApi
internal inline fun <reified Bracket : ScopeChangingToken, T : Token> getStickyHeaderLines(
    line: Int,
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    crossinline stickyHeaderLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] },
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
                if ((state.tokenPositions[token] ?: continue).second.line >= topLine + 1) break
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
        stickyHeaderLinesChooser(bracket) ?: IntRange.EMPTY
    }.sorted().toSet()
}

public inline fun <reified Bracket : ScopeChangingToken, T : Token> getOffsetForLineToAppearOnTop(
    line: Int,
    textSize: Size,
    density: Density,
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    dividerThickness: Dp,
    maximumStickyHeaderHeight: Dp,
    crossinline stickyHeaderLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] }
): Int {
    val resultLine = (line downTo 0).firstOrNull { attemptLine ->
        val height = getStickyHeaderHeight(
            attemptLine,
            textSize,
            density,
            state,
            matchedBrackets,
            dividerThickness,
            maximumStickyHeaderHeight,
            stickyHeaderLinesChooser
        )
        (line - attemptLine) * textSize.height >= height
    } ?: 0
    return ((line - resultLine) * textSize.height).roundToInt()
}


@PublishedApi
internal inline fun <reified Bracket : ScopeChangingToken, T : Token> getStickyHeaderHeight(
    line: Int,
    textSize: Size,
    density: Density,
    state: BasicSourceCodeTextFieldState<T>,
    matchedBrackets: Map<Bracket, Bracket>,
    dividerThickness: Dp,
    maximumStickyHeaderHeight: Dp,
    crossinline stickyHeaderLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] }
): Int {
    val stickyHeaderLines = getStickyHeaderLines<Bracket, T>(line, state, matchedBrackets, stickyHeaderLinesChooser)
    if (stickyHeaderLines.isEmpty()) return 0
    return with(density) {
        minOf(stickyHeaderLines.size * textSize.height + dividerThickness.toPx(), maximumStickyHeaderHeight.toPx())
    }.roundToInt()
}

@Composable
public inline fun <reified Bracket : ScopeChangingToken, T : Token> BoxWithConstraintsScope.StickyHeader(
    state: BasicSourceCodeTextFieldState<T>,
    textStyle: TextStyle,
    lineNumbersColor: Color,
    backgroundColor: Color,
    scrollState: ScrollState,
    showLineNumbers: Boolean,
    matchedBrackets: Map<Bracket, Bracket>,
    divider: @Composable () -> Unit,
    maximumStickyHeaderHeight: Dp = maxHeight / 3,
    lineNumberModifier: Modifier = defaultLineNumberModifier,
    lineStringModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    crossinline stickyHeaderLinesChooser: (Bracket) -> IntRange? = { bracket -> state.tokenLines[bracket as T] },
    crossinline onClick: (lineNumber: Int) -> Unit = {},
    crossinline onHoveredSourceCodePositionChange: (position: SourceCodePosition) -> Unit = {},
    crossinline additionalInnerComposable: @Composable BoxWithConstraintsScope.(linesToWrite: Map<Int, AnnotatedString>, inner: @Composable () -> Unit) -> Unit = { _, _ -> },
) {
    val measuredText = measureText(textStyle)
    val textHeightDp = with(LocalDensity.current) { measuredText.height.toDp() }
    if (scrollState.value == 0) return
    val topVisibleRow = (scrollState.value / measuredText.height).toInt()
    val requestedLinesSet = getStickyHeaderLines(topVisibleRow, state, matchedBrackets, stickyHeaderLinesChooser)
    if (requestedLinesSet.isEmpty()) return
    Column {
        Column(Modifier.heightIn(max = maximumStickyHeaderHeight)) {
            Row(
                modifier = Modifier
                    .width(this@StickyHeader.maxWidth)
                    .verticalScroll(rememberScrollState())
                    .background(backgroundColor)
            ) {
                val lineCount: Int = state.offsets.size
                val linesToWrite = requestedLinesSet.associateWith { lineNumber ->
                    val lineOffsets =
                        state.offsets[lineNumber].takeIf { it.isNotEmpty() } ?: return@associateWith AnnotatedString("")
                    val annotatedString = visualTransformation.filter(state.annotatedString).text
                    val lastOffset =
                        if (lineOffsets.last() == annotatedString.lastIndex) annotatedString.length else lineOffsets.last()
                    buildAnnotatedString {
                        append(annotatedString, lineOffsets.first(), lastOffset)
                    }
                }
                AnimatedVisibility(showLineNumbers) {
                    Column {
                        for ((lineNumber, _) in linesToWrite) {
                            Box(modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onClick(lineNumber) }
                                )
                            ) {
                                val lineNumbersWidth = with(LocalDensity.current) {
                                    (lineCount.toString().length * measuredText.width).toDp()
                                }
                                BasicText(
                                    text = "${lineNumber.inc()}",
                                    style = textStyle.copy(color = lineNumbersColor, textAlign = TextAlign.End),
                                    modifier = lineNumberModifier
                                        .width(lineNumbersWidth)
                                        .height(textHeightDp)
                                )
                            }
                        }
                    }
                }
                BoxWithConstraints {
                    val outerScope = this
                    BoxWithConstraints(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        val innerScope = this
                        var maxWidth by remember { mutableStateOf(outerScope.maxWidth) }
                        Wrapper(
                            content = { innerComposable ->
                                innerScope.additionalInnerComposable(linesToWrite, innerComposable)
                            }
                        ) {
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
                                            modifier = lineStringModifier
                                                .height(textHeightDp)
                                                .widthIn(min = maxWidth)
                                                .layout { measurable, constraints ->
                                                    val placeable = measurable.measure(constraints)
                                                    maxWidth = max(maxWidth, placeable.width.toDp())

                                                    layout(placeable.width, placeable.height) {
                                                        placeable.placeRelative(0, 0)
                                                    }
                                                }
                                                .onPointerOffsetChange {
                                                    val sourceCodePosition = SourceCodePosition(
                                                        line = lineNumber,
                                                        column = (it.x / measuredText.width).toInt()
                                                    )
                                                    onHoveredSourceCodePositionChange(sourceCodePosition)
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        divider()
    }
}
