package editor.basic

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange


public fun <T : Token> getCurrentPositionTokens(
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

public inline fun <reified Bracket : ScopeChangingToken> matchBrackets(tokens: List<Token>): Map<Bracket, Bracket> {
    val unorderedMatchedBrackets = mutableMapOf<Bracket, Bracket>()
    val openingBracketsStack = mutableListOf<Bracket>()
    val order = mutableListOf<Bracket>()
    for (token in tokens) {
        if (token is Bracket) {
            order.add(token)
            when (token.scopeChange) {
                ScopeChange.OpensScope -> openingBracketsStack.add(token)
                ScopeChange.ClosesScope -> {
                    val opening = openingBracketsStack.lastOrNull { it.matches(token) } ?: continue
                    unorderedMatchedBrackets[opening] = token
                    unorderedMatchedBrackets[token] = opening
                    while (openingBracketsStack.last() != opening) {
                        openingBracketsStack.removeLast()
                    }
                    openingBracketsStack.removeLast()
                }
            }
        }
    }
    return buildMap {
        for (bracket in order) {
            val matchingBracket = unorderedMatchedBrackets[bracket] ?: continue
            put(bracket, matchingBracket)
        }
    }
}

public fun <Bracket> updateMatchingBracketsStyle(
    openingToClosingBrackets: Map<Bracket, Bracket>,
    styleUpdator: (index: Int, depth: Int, openingStyle: SpanStyle, closingStyle: SpanStyle) -> Pair<SpanStyle, SpanStyle>
) where Bracket : ScopeChangingToken, Bracket : SingleStyleToken {
    var depth = 0
    openingToClosingBrackets.entries.forEachIndexed { index, (opening, closing) ->
        if (opening.scopeChange != ScopeChange.OpensScope) {
            depth--
            return@forEachIndexed
        }
        val (openingStyle, closingStyle) = styleUpdator(index, depth, opening.style, closing.style)
        opening.style = openingStyle
        closing.style = closingStyle
        depth++
    }
}

public inline fun <reified Bracket> updateMatchingBracesAtCurrentPositionStyle(
    selectedTokens: List<Token>,
    matchedBrackets: Map<Bracket, Bracket>,
    bracketFilter: (List<Bracket>) -> List<Bracket> = { it },
    styleUpdater: (SpanStyle) -> SpanStyle,
) where Bracket : ScopeChangingToken, Bracket : SingleStyleToken {
    for (selectedToken in selectedTokens.filterIsInstance<Bracket>().let(bracketFilter)) {
        selectedToken.style = styleUpdater(selectedToken.style)
        matchedBrackets[selectedToken]?.let { it.style = styleUpdater(it.style) }
    }
}

public inline fun <reified S> updateSameSymbolsWithOnesAtCurrentPosition(
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
