package editor.basic

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
data class BasicSourceCodeTextFieldState<out T : Token>(
    val tokens: List<T> = emptyList(),
    val selection: TextRange = TextRange.Zero,
    val composition: TextRange? = null,
)

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

@Composable
fun <T : Token> BasicSourceCodeTextField(
    state: BasicSourceCodeTextFieldState<T>,
    onStateUpdate: (new: BasicSourceCodeTextFieldState<T>) -> Unit,
    preprocessors: List<Preprocessor> = emptyList(),
    tokenize: Tokenizer<T>,
    additionalComposable: @Composable BoxWithConstraintsScope.(state: BasicSourceCodeTextFieldState<T>, textLayoutResult: TextLayoutResult?) -> Unit = { _, _ -> },
    format: suspend (List<T>) -> FormattedCode<T> = { FormattedCode.AsTokens(it) },
    pinScopeOpeningLines: Boolean = true,
    showLineNumbers: Boolean = true,
    textStyle: TextStyle = TextStyle.Default.copy(fontFamily = FontFamily.Monospace),
) {
    val textState = TextFieldValue(
        annotatedString = buildAnnotatedString { state.tokens.forEach { append(it.annotatedString) } },
        selection = state.selection,
        composition = state.composition
    )

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    var textLayout: TextLayoutResult? by remember(textState.text) { mutableStateOf(null) }
    Row(
        modifier = Modifier.verticalScroll(verticalScroll).background(color = Color.White)
    ) {
        if (showLineNumbers) {
            Column(horizontalAlignment = Alignment.End) {
                repeat(textState.text.count { it == '\n' } + 1) {
                    BasicText(
                        text = "${it.inc()}",
                        style = textStyle,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        BoxWithConstraints(modifier = Modifier.horizontalScroll(horizontalScroll)) {
            BasicTextField(
                value = textState,
                onValueChange = { onStateUpdate(tokenize(preprocessors.fold(it) { acc, preprocessor -> preprocessor(acc) })) },
                maxLines = Int.MAX_VALUE,
                textStyle = textStyle,
                cursorBrush = SolidColor(Color.Black),
                onTextLayout = { textLayout = it },
            )
            additionalComposable(state, textLayout)
        }
    }
}

private data class SourceCodePosition(val line: Int, val column: Int)

private fun <Bracket : ScopeChangingToken<Bracket>> getIndentationLines(
    state: BasicSourceCodeTextFieldState<Token>,
    matchedBrackets: Map<Bracket, Bracket>,
): List<SourceCodePosition> {
    val textRanges = mutableMapOf<Token, Pair<Int, Int>>()
    state.tokens.joinToString("") { it.text }
    val lineOffsets = buildList {
        add(null)
        var currentOffset = 0
        val currentLine by ::lastIndex
        var currentColumn = 0
        for (token in state.tokens) {
            val startLine = lastIndex
            for (char in token.text) {
                if (char == '\n') {
                    add(null)
                    currentColumn = 0
                } else {
                    if (!char.isWhitespace() && last() == null) {
                        set(currentLine, currentColumn)
                    }
                    currentColumn++
                }
                currentOffset++
            }

            if (matchedBrackets.containsKey(token)) {
                textRanges[token] = startLine to currentLine
            }
        }
    }

    return buildList {
        for ((opening, closing) in matchedBrackets) {
            if (opening.scopeChange != ScopeChange.OpensScope) continue
            val (openingStartLine, openingEndLine) = textRanges[opening] ?: continue
            val (closingStartLine, _) = textRanges[closing] ?: continue
            val originalRange = (openingEndLine + 1)..closingStartLine
            val columnOffset =
                (openingStartLine..openingEndLine).mapNotNull { lineOffsets[it] }.minOrNull() ?: continue
            for (line in originalRange) {
                val offset = lineOffsets[line]
                if (offset == null || offset > columnOffset) {
                    add(SourceCodePosition(line, columnOffset))
                }
            }
        }
    }
}


@Composable
fun <Bracket : ScopeChangingToken<Bracket>> BoxScope.IndentationLines(
    state: BasicSourceCodeTextFieldState<Token>,
    matchedBrackets: Map<Bracket, Bracket>,
    textLayoutResult: TextLayoutResult?,
    modifier: Modifier,
    distinct: Boolean = false,
    width: Dp = 1.dp,
    textStyle: TextStyle,
) {
    if (textLayoutResult == null) return
    val measured = rememberTextMeasurer().measure("a", textStyle)
    val letterHeight = measured.getLineBottom(0) - measured.getLineTop(0)
    val letterWidth = measured.size.width
    val indentationLines = getIndentationLines(state, matchedBrackets).let { if (distinct) it.distinct() else it }

    for ((line, column) in indentationLines) {
        val bottom = letterHeight * line.inc()
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
