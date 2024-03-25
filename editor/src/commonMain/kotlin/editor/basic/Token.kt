package editor.basic

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle


public enum class ScopeChange {
    OpensScope, ClosesScope
}

public interface Token {
    public val annotatedString: AnnotatedString
}

public val Token.text: String get() = annotatedString.text

public interface WhiteSpaceToken : Token

public interface ScopeChangingToken : Token {
    public val scopeChange: ScopeChange
    public fun matches(token: Token): Boolean
}

public interface SymbolToken<T : Token> : Token {
    public fun isSameSymbolWith(symbol: T): Boolean
}

public interface SingleStyleToken : Token {
    public val text: String
    public var style: SpanStyle
    override val annotatedString: AnnotatedString
        get() = AnnotatedString(text, style)
}
