import CorrectBracketSequenceToken.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import editor.basic.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch


private const val openingBrackets = "([{"
private const val closingBrackets = ")]}"

private sealed class CorrectBracketSequenceToken : SingleStyleToken {
    class WhiteSpace(val whiteSpace: Char) : CorrectBracketSequenceToken(), WhiteSpaceToken {
        override val text: String = whiteSpace.toString()
        override var style: SpanStyle = SpanStyle()
    }

    class Identifier(
        val identifier: String, override var style: SpanStyle = SpanStyle()
    ) : CorrectBracketSequenceToken(), SymbolToken<Identifier> {
        override val text: String get() = identifier
        override fun isSameSymbolWith(symbol: Identifier): Boolean = this.identifier == symbol.identifier
    }

    class Bracket(
        val bracket: Char,
        override var style: SpanStyle = SpanStyle(),
        override val scopeChange: ScopeChange
    ) : CorrectBracketSequenceToken(), ScopeChangingToken {
        private val alterBracket = when (scopeChange) {
            ScopeChange.OpensScope -> closingBrackets[openingBrackets.indexOf(bracket)]
            ScopeChange.ClosesScope -> openingBrackets[closingBrackets.indexOf(bracket)]
        }
        override val text: String get() = bracket.toString()
        override fun matches(token: Token): Boolean = token is Bracket &&
                token.scopeChange != this.scopeChange && token.bracket == this.alterBracket
    }

    class Miscellaneous(val other: Char) : CorrectBracketSequenceToken() {
        override val text: String = other.toString()
        override var style: SpanStyle = SpanStyle()
    }
}

private fun tokenize(textFieldState: TextFieldValue): List<CorrectBracketSequenceToken> = buildList {
    val text = textFieldState.text
    var i = 0
    while (i < text.length) {
        val c = text[i]
        fun Char.isOkForIdentifier() = isLetterOrDigit() || this == '_'
        when {
            c.isOkForIdentifier() -> {
                val string = buildString {
                    while (i < text.length && text[i].isOkForIdentifier()) {
                        append(text[i++])
                    }
                }
                add(Identifier(string))
                continue
            }

            c in openingBrackets -> add(Bracket(c, scopeChange = ScopeChange.OpensScope))
            c in closingBrackets -> add(Bracket(c, scopeChange = ScopeChange.ClosesScope))
            else -> add(Miscellaneous(c))
        }
        i++
    }
}

private fun tokenizationPipeline(textFieldState: TextFieldValue): BasicSourceCodeTextFieldState<CorrectBracketSequenceToken> {
    val tokens = tokenize(textFieldState)
    val matchingBrackets = matchBrackets<Bracket>(tokens)

    val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Cyan, Color.Magenta)
    updateMatchingBracketsStyle(matchingBrackets) { _, depth, openingStyle, closingStyle ->
        openingStyle.copy(color = colors[depth % colors.size]) to closingStyle.copy(color = colors[depth % colors.size])
    }

    val currentTokens = getCurrentPositionTokens(textFieldState.selection, tokens)
    updateMatchingBracesAtCurrentPositionStyle(currentTokens, matchingBrackets) {
        it.copy(background = Color.LightGray)
    }
    updateSameSymbolsWithOnesAtCurrentPosition<Identifier>(currentTokens, tokens) {
        it.copy(textDecoration = TextDecoration.Underline)
    }

    return BasicSourceCodeTextFieldState(tokens, textFieldState.selection, textFieldState.composition)
}

@Composable
fun CorrectBracketSequence() {
    var codeTextFieldState by remember { mutableStateOf(BasicSourceCodeTextFieldState<CorrectBracketSequenceToken>()) }
    val textStyle = TextStyle.Default.copy(fontFamily = FontFamily.Monospace)
    val verticalState = rememberScrollState()
    val matchedBrackets = matchBrackets<Bracket>(codeTextFieldState.tokens)
    val indentationLines = getIndentationLines(codeTextFieldState, matchedBrackets, false)
    val coroutineScope = rememberCoroutineScope()
    val externalScrollToFlow = remember { MutableSharedFlow<SourceCodePosition>() }
    val showLineNumbers by remember { mutableStateOf(true) }
    val stickyHeader by remember { mutableStateOf(true) }
    val showIndentation by remember { mutableStateOf(true) }
    val textSize = measureText(textStyle)
    val density = LocalDensity.current
    val stickyHeaderLinesChooser: (Bracket) -> IntRange? = { bracket ->
        if (bracket.bracket in "{}") codeTextFieldState.tokenLines[bracket] else null
    }
    var maximumStickyHeaderHeight: Dp by remember { mutableStateOf(0.dp) }

    val lineNumbersColor = Color.DarkGray
    val innerPadding = PaddingValues(10.dp)
    val stickyHeaderInnerPadding = PaddingValues(
        start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        end = innerPadding.calculateEndPadding(LocalLayoutDirection.current),
        top = innerPadding.calculateTopPadding(),
    )
    BasicSourceCodeTextField(
        state = codeTextFieldState,
        onStateUpdate = { codeTextFieldState = it },
        preprocessors = listOf({ replaceTabs(it) }),
        tokenize = { tokenizationPipeline(it) },
        additionalInnerComposable = { _, _ ->
            AnimatedVisibility(showIndentation) {
                IndentationLines(
                    indentationLines = indentationLines,
                    modifier = Modifier.background(color = Color.Gray),
                    textStyle = textStyle,
                )
            }
        },
        lineNumbersColor = lineNumbersColor,
        manualScrollToPosition = externalScrollToFlow,
        additionalOuterComposable = { _, inner ->
            inner()
            AnimatedVisibility(stickyHeader) {
                StickyHeader(
                    state = codeTextFieldState,
                    textStyle = textStyle,
                    lineNumbersColor = lineNumbersColor,
                    backgroundColor = Color.White,
                    scrollState = verticalState,
                    showLineNumbers = showLineNumbers,
                    matchedBrackets = matchedBrackets,
                    stickyHeaderLinesChooser = stickyHeaderLinesChooser,
                    maximumStickyHeaderHeight = (maxHeight / 3).also { maximumStickyHeaderHeight = it },
                    onClick = { coroutineScope.launch { externalScrollToFlow.emit(SourceCodePosition(it, 0)) } },
                    divider = { HorizontalDivider(thickness = 1.dp) },
                    innerPadding = stickyHeaderInnerPadding,
                    additionalInnerComposable = { linesToWrite, _ ->
                        AnimatedVisibility(showIndentation) {
                            val lineMapping = linesToWrite.keys.withIndex().associate { (index, line) -> line to index }
                            IndentationLines(
                                indentationLines = indentationLines,
                                modifier = Modifier.background(color = Color.Gray),
                                textStyle = textStyle,
                                mapLineNumbers = lineMapping::get,
                            )
                        }
                    },
                )
            }
        },
        showLineNumbers = showLineNumbers,
        textStyle = textStyle,
        verticalScrollState = verticalState,
        modifier = Modifier.fillMaxWidth().background(color = Color.White),
        innerPadding = innerPadding,
        editorOffsetsForPosition = {
            EditorOffsets(
                top = getOffsetForLineToAppearOnTop(
                    line = it.line,
                    textSize = textSize,
                    density = density,
                    state = codeTextFieldState,
                    matchedBrackets = matchedBrackets,
                    dividerThickness = 0.dp, // do not include divider thickness in the calculation
                    maximumStickyHeaderHeight = maximumStickyHeaderHeight,
                    stickyHeaderLinesChooser = stickyHeaderLinesChooser,
                )
            )
        },
        keyEventHandler = combineKeyEventHandlers(
            handleMovingOffsets(
                state = codeTextFieldState,
            )
        ),
        charEventHandler = combineCharEventHandlers(
            reusingCharsEventHandler(
                textFieldState = codeTextFieldState,
                chars = "])>}",
            ),
            openingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingChar = '[',
                openingBracket = "[",
                closingBracket = "]",
                addNewLinesForSelection = { false },
            ),
            closingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "[",
                closingBracket = "]",
                closingChar = ']',
                matchedBrackets = matchedBrackets,
            ),
            openingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingChar = '(',
                openingBracket = "(",
                closingBracket = ")",
                addNewLinesForSelection = { false },
            ),
            closingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "(",
                closingBracket = ")",
                closingChar = ')',
                matchedBrackets = matchedBrackets,
            ),
            openingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingChar = '<',
                openingBracket = "<",
                closingBracket = ">",
                addNewLinesForSelection = { false },
            ),
            closingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "<",
                closingBracket = ">",
                closingChar = '>',
                matchedBrackets = matchedBrackets,
            ),
            openingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingChar = '{',
                openingBracket = "{",
                closingBracket = "}",
                addNewLinesForSelection = { true },
            ),
            closingBracketCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "{",
                closingBracket = "}",
                closingChar = '}',
                matchedBrackets = matchedBrackets,
            ),
            newLineCharEventHandler(
                textFieldState = codeTextFieldState,
                matchedBrackets = matchedBrackets,
            ),
            removeIndentBackspaceCharEventHandler(
                textFieldState = codeTextFieldState,
            ),
            removeEmptyBracesBackspaceCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "[",
                closingBracket = "]",
            ),
            removeEmptyBracesBackspaceCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "(",
                closingBracket = ")",
            ),
            removeEmptyBracesBackspaceCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "<",
                closingBracket = ">",
            ),
            removeEmptyBracesBackspaceCharEventHandler(
                textFieldState = codeTextFieldState,
                openingBracket = "{",
                closingBracket = "}",
            ),
        ),
    )
}
