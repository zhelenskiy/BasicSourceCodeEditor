package editor.basic

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle


enum class ScopeChange {
                       OpensScope, ClosesScope
}

interface Token {
    val annotatedString: AnnotatedString
}

val Token.text: String get() = annotatedString.text

interface WhiteSpaceToken : Token

interface ScopeChangingToken : Token {
    val scopeChange: ScopeChange
    fun matches(token: Token): Boolean
}

interface SymbolToken<T : Token> : Token {
    fun isSameSymbolWith(symbol: T): Boolean
}

abstract class SingleStyleToken : Token {
    abstract val text: String
    open var style: SpanStyle = SpanStyle()
    override fun toString(): String = text
    override val annotatedString: AnnotatedString
        get() = AnnotatedString(text, style)
}