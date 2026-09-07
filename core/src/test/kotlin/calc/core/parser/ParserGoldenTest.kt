package calc.core.parser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden tests: input -> expected canonical AST.
 *
 * Comparison is on normalised ASTs, never rendered strings (see AstNormalisation.kt).
 */
class ParserGoldenTest {

    private fun golden(vararg cases: Pair<String, Expr>) {
        for ((src, expected) in cases) assertEquals(expected, exprOf(src), "input: $src")
    }

    @Test
    fun `arithmetic precedence`() = golden(
        "1 + 2" to Binary(BinOp.ADD, n("1"), n("2")),
        "2 * 3 + 4" to Binary(BinOp.ADD, Binary(BinOp.MUL, n("2"), n("3")), n("4")),
        "2 + 3 * 4" to Binary(BinOp.ADD, n("2"), Binary(BinOp.MUL, n("3"), n("4"))),
        "8 / 2 - 1" to Binary(BinOp.SUB, Binary(BinOp.DIV, n("8"), n("2")), n("1")),
        "(2 + 3) * 4" to Binary(BinOp.MUL, Binary(BinOp.ADD, n("2"), n("3")), n("4")),
    )

    @Test
    fun `power is right-associative and binds tighter than unary minus`() = golden(
        "2 ^ 3 ^ 2" to Binary(BinOp.POW, n("2"), Binary(BinOp.POW, n("3"), n("2"))),
        "-2 ^ 2" to Unary(UnOp.NEGATE, Binary(BinOp.POW, n("2"), n("2"))),
        "-5 + 3" to Binary(BinOp.ADD, Unary(UnOp.NEGATE, n("5")), n("3")),
    )

    @Test
    fun `unary plus is a no-op`() = golden(
        "+5" to n("5"),
    )

    @Test
    fun `percent-relative operators bind tighter than additive`() = golden(
        "20% of 100 + 5" to Binary(BinOp.ADD, Binary(BinOp.PERCENT_OF, pct("20"), n("100")), n("5")),
        "20% on 100 + 5" to Binary(BinOp.ADD, Binary(BinOp.PERCENT_ON, pct("20"), n("100")), n("5")),
    )

    @Test
    fun `percent-relative operators bind looser than multiplicative`() = golden(
        "20% of 2 * 50" to Binary(BinOp.PERCENT_OF, pct("20"), Binary(BinOp.MUL, n("2"), n("50"))),
    )

    @Test
    fun `unit suffixes bind tighter than every operator`() = golden(
        "5 mi" to q("5", "mi"),
        "5 mi / 17 mph" to Binary(BinOp.DIV, q("5", "mi"), q("17", "mph")),
        "5 mi / 17mph" to Binary(BinOp.DIV, q("5", "mi"), q("17", "mph")),
        "350 MB / 8 Mbps" to Binary(BinOp.DIV, q("350", "MB"), q("8", "Mbps")),
        "5 km + 300 m" to Binary(BinOp.ADD, q("5", "km"), q("300", "m")),
    )

    @Test
    fun `unit names are kept as written for the evaluator to resolve`() = golden(
        "5 miles" to q("5", "miles"),
        "5 MI" to q("5", "MI"),
    )

    @Test
    fun `per is division`() = golden(
        "10 mi per 2 h" to Binary(BinOp.DIV, q("10", "mi"), q("2", "h")),
    )

    @Test
    fun `a bare word is an unresolved identifier`() = golden(
        "3 mi per hour" to Binary(BinOp.DIV, q("3", "mi"), Ident("hour")),
        "x + 1" to Binary(BinOp.ADD, Ident("x"), n("1")),
    )

    @Test
    fun `conversion keywords are interchangeable`() {
        val expected = Convert(q("5", "km"), "mi")
        golden(
            "5 km in mi" to expected,
            "5 km to mi" to expected,
            "5 km as mi" to expected,
        )
    }

    @Test
    fun `conversion binds loosest`() = golden(
        "50 USD in GBP" to Convert(q("50", "USD"), "GBP"),
        "1 km + 5 m in ft" to Convert(Binary(BinOp.ADD, q("1", "km"), q("5", "m")), "ft"),
    )

    @Test
    fun `currency symbols attach to the following amount`() = golden(
        "\$50" to UnitApply(n("50"), "$"),
        "£50 + £3" to Binary(BinOp.ADD, UnitApply(n("50"), "£"), UnitApply(n("3"), "£")),
        "-\$50" to Unary(UnOp.NEGATE, UnitApply(n("50"), "$")),
    )

    @Test
    fun `magnitude words fold into the preceding number`() = golden(
        "1 million" to n("1000000"),
        "1.2 million" to n("1200000"),
        "1 hundred thousand" to n("100000"),
        "37% of 1 million" to Binary(BinOp.PERCENT_OF, pct("37"), n("1000000")),
    )

    @Test
    fun `magnitude words fold after a currency symbol too`() = golden(
        "\$1.2 million" to UnitApply(n("1200000"), "$"),
        "€1 hundred thousand" to UnitApply(n("100000"), "€"),
        "\$1.2 million + \$300 thousand" to
            Binary(BinOp.ADD, UnitApply(n("1200000"), "$"), UnitApply(n("300000"), "$")),
    )

    @Test
    fun `a symbol-prefixed and a code-suffixed amount agree`() {
        val symbol = exprOf("\$1.2 million") as UnitApply
        val code = exprOf("1.2 million USD") as UnitApply
        assertEquals(symbol.magnitude, code.magnitude)
    }

    @Test
    fun `digit separators do not change the value`() = golden(
        "1,234.5 + 1_000" to Binary(BinOp.ADD, n("1234.5"), n("1000")),
    )

    @Test
    fun `unicode operators parse as their ASCII equivalents`() = golden(
        "3 × 4" to Binary(BinOp.MUL, n("3"), n("4")),
        "8 ÷ 2" to Binary(BinOp.DIV, n("8"), n("2")),
    )

    @Test
    fun `skip words do not affect the parse`() {
        assertEquals(exprOf("20% of 80"), exprOf("what is 20% of 80"))
        assertEquals(exprOf("20% of 80"), exprOf("whats 20% of 80 please"))
    }

    @Test
    fun `assignment binds a name`() {
        assertEquals(
            Statement.Assignment("total", Binary(BinOp.ADD, q("5", "km"), q("300", "m"))),
            Parser.parse("total = 5 km + 300 m").normalised(),
        )
    }

    @Test
    fun `an assignment is detected by lookahead, not by parsing an expression first`() {
        assertEquals(Statement.Assignment("x", n("1")), Parser.parse("x = 1").normalised())
        assertEquals(Statement.Expression(Ident("x")), Parser.parse("x").normalised())
    }

    @Test
    fun `blank lines produce no statement`() {
        assertEquals(Statement.Blank, Parser.parse(""))
        assertEquals(Statement.Blank, Parser.parse("    "))
        assertEquals(Statement.Blank, Parser.parse("the"))
    }
}
