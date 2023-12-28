import CorrectBracketSequenceToken.*
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import editor.basic.*


private const val openingBrackets = "([{"
private const val closingBrackets = ")]}"

private sealed class CorrectBracketSequenceToken : SingleStyleToken() {
    class WhiteSpace(val whiteSpace: Char) : CorrectBracketSequenceToken(), WhiteSpaceToken {
        override val text: String = whiteSpace.toString()
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
    ) : CorrectBracketSequenceToken(), ScopeChangingToken<Bracket> {
        private val alterBracket = when (scopeChange) {
            ScopeChange.OpensScope -> closingBrackets[openingBrackets.indexOf(bracket)]
            ScopeChange.ClosesScope -> openingBrackets[closingBrackets.indexOf(bracket)]
        }
        override val text: String get() = bracket.toString()
        override fun matches(token: Bracket): Boolean =
            token.scopeChange != this.scopeChange && token.bracket == this.alterBracket
    }

    class Miscellaneous(val other: Char) : CorrectBracketSequenceToken() {
        override val text: String = other.toString()
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
    updateMatchingBracketsStyle(matchingBrackets) { index, openingStyle, closingStyle ->
        openingStyle.copy(color = colors[index % colors.size]) to closingStyle.copy(color = colors[index % colors.size])
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
    
    BasicSourceCodeTextField(
        state = codeTextFieldState,
        onStateUpdate = { codeTextFieldState = it },
        tokenize = { tokenizationPipeline(it) },
        preprocessors = listOf({ replaceTabs(it) }),
        textStyle = textStyle,
        additionalComposable = { state, textLayoutResult ->
            IndentationLines(state, matchBrackets<Bracket>(state.tokens), textLayoutResult, textStyle = textStyle, modifier = Modifier.background(color = Color.Gray))
        }
    )
}