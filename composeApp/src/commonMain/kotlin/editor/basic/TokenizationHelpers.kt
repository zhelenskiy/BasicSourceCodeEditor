package editor.basic

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange


fun <T : Token> getCurrentPositionTokens(
    selection: TextRange,
    tokens: List<T>,
): List<T> = buildList {
    var offset = 0
    var hasFound = false
    for (token in tokens) {
        val text = token.text
        val tokenRange = TextRange(offset, offset + text.length)
        if (tokenRange.contains(selection)) {
            hasFound = true
            add(token)
        } else if (hasFound) {
            break
        }
        offset += text.length
    }
}

inline fun <reified Bracket : ScopeChangingToken<Bracket>> matchBrackets(tokens: List<Token>): Map<Bracket, Bracket> {
    val matchedBrackets = mutableMapOf<Bracket, Bracket>()
    val openingBracketsStack = mutableListOf<Bracket>()
    for (token in tokens) {
        if (token is Bracket) {
            when (token.scopeChange) {
                ScopeChange.OpensScope -> openingBracketsStack.add(token)
                ScopeChange.ClosesScope -> {
                    val opening = openingBracketsStack.lastOrNull() ?: continue
                    if (opening.matches(token)) {
                        matchedBrackets[opening] = token
                        matchedBrackets[token] = opening
                        openingBracketsStack.removeLast()
                    }
                }
            }
        }
    }
    return matchedBrackets
}

fun <Bracket> updateMatchingBracketsStyle(
    openingToClosingBrackets: Map<Bracket, Bracket>,
    styleUpdator: (index: Int, openingStyle: SpanStyle, closingStyle: SpanStyle) -> Pair<SpanStyle, SpanStyle>
) where Bracket : ScopeChangingToken<Bracket>, Bracket : SingleStyleToken {
    openingToClosingBrackets.entries.forEachIndexed { index, (opening, closing) ->
        val (openingStyle, closingStyle) = styleUpdator(index, opening.style, closing.style)
        opening.style = openingStyle
        closing.style = closingStyle
    }
}

inline fun <reified Bracket> updateMatchingBracesAtCurrentPositionStyle(
    selectedTokens: List<Token>,
    matchedBrackets: Map<Bracket, Bracket>,
    bracketFilter: (List<Bracket>) -> List<Bracket> = { it },
    styleUpdater: (SpanStyle) -> SpanStyle,
) where Bracket : ScopeChangingToken<Bracket>, Bracket : SingleStyleToken {
    for (selectedToken in selectedTokens.filterIsInstance<Bracket>().let(bracketFilter)) {
        selectedToken.style = styleUpdater(selectedToken.style)
        matchedBrackets[selectedToken]?.let { it.style = styleUpdater(it.style) }
    }
}

inline fun <reified S> updateSameSymbolsWithOnesAtCurrentPosition(
    currentTokens: List<Token>,
    tokens: List<Token>,
    symbolFilter: (List<S>) -> List<S> = { it.singleOrNull().let(::listOfNotNull) },
    styleUpdater: (SpanStyle) -> SpanStyle,
) where S : SymbolToken<S>, S : SingleStyleToken {
    val identifiers = currentTokens.filterIsInstance<S>().let(symbolFilter)
    for (identifier in identifiers) {
        for (token in tokens) {
            if (token is S && identifier.isSameSymbolWith(token)) {
                token.style = styleUpdater(token.style)
            }
        }
    }
}
