package calc.core.lexer

import java.math.BigDecimal

enum class TokenType { NUMBER, WORD, KEYWORD, OPERATOR, PERCENT, LPAREN, RPAREN, EQUALS, EOF }

/** Words with grammatical meaning. Anything else is a WORD and must resolve to a unit or variable. */
enum class Keyword {
    OF,      // 37% of 1 million        -> multiply
    ON,      // 20% on 11.49            -> add to  (NOT the same as OF; Numi conflates them)
    IN,      // 50 USD in GBP           -> convert
    TO,      // 5 km to mi              -> convert
    AS,      // 5 km as mi              -> convert
    PER,     // 3 mi per hour           -> divide
    AT,      // recognised, unsupported -> see ParseError.Unsupported
}

data class Token(
    val type: TokenType,
    val text: String,
    val pos: Int,
    val number: BigDecimal? = null,
    val keyword: Keyword? = null,
)

class LexError(message: String, val pos: Int) : Exception(message)

/**
 * The closed skip set.
 *
 * This is the whole discipline. Numi drops any token it fails to recognise, which is
 * why `20% garbage on 11.49` confidently returns 13.79. Here, only these words are
 * dropped; every other unrecognised word survives as a WORD token and fails to
 * resolve, so the line yields no result instead of a wrong one.
 *
 * Grow this list deliberately. Never make it open-ended.
 */
val SKIP_WORDS: Set<String> = setOf(
    "the", "a", "an", "is", "are", "was", "be",
    "and", "then", "please", "just",
    "what", "whats", "how", "much", "many",
)

/** Scale words folded into the preceding number: `1.2 million` -> 1200000. */
val MAGNITUDE_WORDS: Map<String, BigDecimal> = mapOf(
    "hundred" to BigDecimal("100"),
    "thousand" to BigDecimal("1000"),
    "million" to BigDecimal("1000000"),
    "billion" to BigDecimal("1000000000"),
    "trillion" to BigDecimal("1000000000000"),
)

private val KEYWORDS: Map<String, Keyword> = mapOf(
    "of" to Keyword.OF,
    "on" to Keyword.ON,
    "in" to Keyword.IN,
    "to" to Keyword.TO,
    "as" to Keyword.AS,
    "per" to Keyword.PER,
    "at" to Keyword.AT,
)

class Lexer(private val src: String) {

    private var i = 0

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            skipSpace()
            if (i >= src.length) break
            val start = i
            val c = src[i]
            when {
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) ->
                    out += number(start)

                c.isLetter() || c == '_' -> word(start)?.let { out += it }

                c == '%' -> { i++; out += Token(TokenType.PERCENT, "%", start) }
                c == '(' -> { i++; out += Token(TokenType.LPAREN, "(", start) }
                c == ')' -> { i++; out += Token(TokenType.RPAREN, ")", start) }
                c == '=' -> { i++; out += Token(TokenType.EQUALS, "=", start) }

                c in "+-*/^×÷" -> {
                    i++
                    val norm = when (c) { '×' -> "*"; '÷' -> "/"; else -> c.toString() }
                    out += Token(TokenType.OPERATOR, norm, start)
                }

                // Currency symbols are prefix markers; the parser attaches them to the next number.
                c in "$£€¥" -> { i++; out += Token(TokenType.WORD, c.toString(), start) }

                else -> throw LexError("Unexpected character '$c'", start)
            }
        }
        out += Token(TokenType.EOF, "", src.length)
        return out
    }

    private fun skipSpace() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun number(start: Int): Token {
        val sb = StringBuilder()
        while (i < src.length && (src[i].isDigit() || src[i] == '_' || src[i] == ',')) {
            if (src[i] != '_' && src[i] != ',') sb.append(src[i])
            i++
        }
        if (i < src.length && src[i] == '.' && i + 1 < src.length && src[i + 1].isDigit()) {
            sb.append('.'); i++
            while (i < src.length && src[i].isDigit()) { sb.append(src[i]); i++ }
        }
        return Token(TokenType.NUMBER, sb.toString(), start, number = BigDecimal(sb.toString()))
    }

    /** Returns null for a skip word — it is dropped, not emitted. */
    private fun word(start: Int): Token? {
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        val text = src.substring(start, i)
        val lower = text.lowercase()
        return when {
            lower in SKIP_WORDS -> null
            lower in KEYWORDS -> Token(TokenType.KEYWORD, text, start, keyword = KEYWORDS[lower])
            else -> Token(TokenType.WORD, text, start)
        }
    }
}
