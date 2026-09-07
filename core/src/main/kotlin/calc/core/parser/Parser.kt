package calc.core.parser

import calc.core.lexer.CURRENCY_SYMBOLS
import calc.core.lexer.Keyword
import calc.core.lexer.Lexer
import calc.core.lexer.MAGNITUDE_WORDS
import calc.core.lexer.Token
import calc.core.lexer.TokenType
import calc.core.model.MC
import calc.core.model.Percent
import calc.core.model.Quantity
import calc.core.units.CurrencyRegistry
import java.math.BigDecimal

sealed class ParseError(message: String, val pos: Int) : Exception(message) {
    class Unexpected(token: Token) : ParseError("Unexpected '${token.text}'", token.pos)
    class Expected(what: String, token: Token) : ParseError("Expected $what, found '${token.text}'", token.pos)

    /**
     * Grammatically recognised but not implemented — `5 miles at 17mph`.
     *
     * Distinct from Unexpected on purpose: this is the class of input that later
     * routes to the normalizer. Unexpected is a genuine error; Unsupported is a
     * to-do the model may one day cover.
     */
    class Unsupported(what: String, pos: Int) : ParseError("Not supported yet: $what", pos)
}

/**
 * Precedence table. Higher binds tighter.
 *
 * `of`/`on` sit between additive and multiplicative so that
 * `20% of 100 + 5` reads as `(20% of 100) + 5`.
 */
private object Bp {
    const val CONVERT = 5
    const val ADDITIVE = 10
    const val PERCENT_REL = 15
    const val MULTIPLICATIVE = 20
    const val UNARY = 25
    const val POWER = 30
}

class Parser(private val tokens: List<Token>) {

    private var i = 0

    private fun peek(): Token = tokens[i]
    private fun next(): Token = tokens[i++]
    private fun at(type: TokenType) = peek().type == type
    private fun atKeyword(vararg kw: Keyword) = peek().type == TokenType.KEYWORD && peek().keyword in kw

    fun parseStatement(): Statement {
        if (at(TokenType.EOF)) return Statement.Blank

        // Assignment is detected by lookahead, not by parsing an expression first:
        // `x = 1` and `x` differ only at the second token.
        if (at(TokenType.WORD) && tokens[i + 1].type == TokenType.EQUALS) {
            val name = next().text
            next() // '='
            val expr = parseExpr(0)
            expectEof()
            return Statement.Assignment(name, expr)
        }

        val expr = parseExpr(0)
        expectEof()
        return Statement.Expression(expr)
    }

    private fun expectEof() {
        if (!at(TokenType.EOF)) throw ParseError.Unexpected(peek())
    }

    /** Pratt loop: parse a prefix, then absorb infix operators that bind tighter than [minBp]. */
    private fun parseExpr(minBp: Int): Expr {
        var left = parsePrefix()

        while (true) {
            val t = peek()
            val (op, bp, rightBp) = when {
                t.type == TokenType.OPERATOR -> when (t.text) {
                    "+" -> Triple(BinOp.ADD, Bp.ADDITIVE, Bp.ADDITIVE + 1)
                    "-" -> Triple(BinOp.SUB, Bp.ADDITIVE, Bp.ADDITIVE + 1)
                    "*" -> Triple(BinOp.MUL, Bp.MULTIPLICATIVE, Bp.MULTIPLICATIVE + 1)
                    "/" -> Triple(BinOp.DIV, Bp.MULTIPLICATIVE, Bp.MULTIPLICATIVE + 1)
                    // right-associative: 2^3^2 is 2^(3^2)
                    "^" -> Triple(BinOp.POW, Bp.POWER, Bp.POWER)
                    else -> break
                }

                t.type == TokenType.KEYWORD -> when (t.keyword) {
                    Keyword.OF -> Triple(BinOp.PERCENT_OF, Bp.PERCENT_REL, Bp.PERCENT_REL + 1)
                    Keyword.ON -> Triple(BinOp.PERCENT_ON, Bp.PERCENT_REL, Bp.PERCENT_REL + 1)
                    Keyword.PER -> Triple(BinOp.DIV, Bp.MULTIPLICATIVE, Bp.MULTIPLICATIVE + 1)

                    Keyword.IN, Keyword.TO, Keyword.AS -> {
                        if (Bp.CONVERT < minBp) break
                        next()
                        val target = expectName("a unit or currency")
                        left = Convert(left, target)
                        continue
                    }

                    // `5 miles at 17mph`: the shape the deterministic parser gives up on.
                    Keyword.AT -> throw ParseError.Unsupported("'at' phrases", t.pos)
                    else -> break
                }

                else -> break
            }

            if (bp < minBp) break
            next()
            val right = parseExpr(rightBp)
            left = Binary(op, left, right)
        }

        return left
    }

    private fun parsePrefix(): Expr {
        val t = next()
        return when {
            t.type == TokenType.NUMBER -> parseNumberTail(t.number!!)

            t.type == TokenType.OPERATOR && t.text == "-" ->
                Unary(UnOp.NEGATE, parseExpr(Bp.UNARY))

            t.type == TokenType.OPERATOR && t.text == "+" -> parseExpr(Bp.UNARY)

            t.type == TokenType.LPAREN -> {
                val inner = parseExpr(0)
                if (!at(TokenType.RPAREN)) throw ParseError.Expected("')'", peek())
                next()
                inner
            }

            // `$50`, `$1.2 million` — currency symbol before the amount.
            //
            // Magnitude words are folded here rather than by falling through to
            // parseNumberTail: the symbol has already fixed the unit, so the tail's
            // `%` and unit-suffix handling must not get a second say.
            t.type == TokenType.WORD && t.text in CURRENCY_SYMBOLS -> {
                if (!at(TokenType.NUMBER)) throw ParseError.Expected("an amount", peek())
                val amount = foldMagnitudeWords(next().number!!)
                UnitApply(Literal(Quantity.scalar(amount)), t.text)
            }

            t.type == TokenType.WORD -> Ident(t.text)

            else -> throw ParseError.Unexpected(t)
        }
    }

    /**
     * After a number: fold magnitude words, then attach `%` or a unit suffix.
     * Unit suffixes bind tighter than every operator, so they are handled here
     * rather than as an infix case.
     */
    private fun parseNumberTail(raw: BigDecimal): Expr {
        val value = foldMagnitudeWords(raw)

        if (at(TokenType.PERCENT)) {
            next()
            return Literal(Percent.ofPercentagePoints(value))
        }

        // A trailing WORD is a unit or currency. Anything else stays unconsumed and
        // becomes an Unexpected token at the top level — which is what rejects
        // `20% garbage on 11.49`.
        if (at(TokenType.WORD)) {
            val w = peek()
            if (CurrencyRegistry.isCurrency(w.text) || calc.core.units.UnitRegistry.isUnit(w.text)) {
                next()
                return UnitApply(Literal(Quantity.scalar(value)), w.text)
            }
        }

        return Literal(Quantity.scalar(value))
    }

    /**
     * Folds scale words that immediately follow a number: `1.2 million` -> 1200000,
     * `1 hundred thousand` -> 100000.
     *
     * Shared by the bare-number and currency-symbol paths so that `$1.2 million` and
     * `1.2 million USD` agree. Only MAGNITUDE_WORDS are consumed; any other word is
     * left for the caller, which is what keeps `1.2 gazillion` a rejection.
     */
    private fun foldMagnitudeWords(raw: BigDecimal): BigDecimal {
        var value = raw
        while (at(TokenType.WORD)) {
            val scale = MAGNITUDE_WORDS[peek().text.lowercase()] ?: break
            next()
            value = value.multiply(scale, MC)
        }
        return value
    }

    private fun expectName(what: String): String {
        val t = peek()
        if (t.type != TokenType.WORD) throw ParseError.Expected(what, t)
        next()
        return t.text
    }

    companion object {
        fun parse(line: String): Statement = Parser(Lexer(line).tokenize()).parseStatement()
    }
}
