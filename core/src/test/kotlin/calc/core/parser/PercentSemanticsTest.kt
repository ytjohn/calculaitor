package calc.core.parser

import calc.core.model.Percent
import calc.core.model.Quantity
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class PercentSemanticsTest {

    private data class Case(
        val input: String,
        val expectedAst: Expr,
        val expectedResult: String,
    )

    private val table = listOf(
        Case("20%", pct("20"), "0.20"),
        Case("11 + 20%", Binary(BinOp.ADD, n("11"), pct("20")), "13.2"),
        Case("11 - 20%", Binary(BinOp.SUB, n("11"), pct("20")), "8.8"),
        Case("20% of 11", Binary(BinOp.PERCENT_OF, pct("20"), n("11")), "2.2"),
        Case("20% on 11", Binary(BinOp.PERCENT_ON, pct("20"), n("11")), "13.2"),
    )

    @Test
    fun `all five percent forms parse to distinct trees`() {
        for (c in table) assertEquals(c.expectedAst, exprOf(c.input), "input: ${c.input}")

        val trees = table.map { exprOf(it.input) }
        assertEquals(trees.size, trees.toSet().size, "percent forms must not collapse")
    }

    @Test
    fun `a percentage survives parse as Percent, not as a scalar`() {
        val bare = exprOf("20%")
        assertIs<Literal>(bare)
        assertIs<Percent>(bare.value)

        val added = exprOf("11 + 20%") as Binary
        assertIs<Percent>((added.right as Literal).value)
    }

    @Test
    fun `of and on are different operators`() {
        val of = exprOf("20% of 11.49") as Binary
        val on = exprOf("20% on 11.49") as Binary

        assertEquals(BinOp.PERCENT_OF, of.op)
        assertEquals(BinOp.PERCENT_ON, on.op)
        assertNotEquals(of, on)
    }

    @Test
    fun `percentage points convert to a fraction`() {
        assertEquals(BigDecimal("0.2"), Percent.ofPercentagePoints(BigDecimal("20")).fraction.stripTrailingZeros())
        assertEquals(BigDecimal("0.375"), Percent.ofPercentagePoints(BigDecimal("37.5")).fraction.stripTrailingZeros())
    }

    @Test
    fun `a percent is not a Quantity`() {
        val p: Any = Percent.ofPercentagePoints(BigDecimal("20"))
        assertEquals(false, p is Quantity)
    }

    @Test
    fun `the table documents the arithmetic the evaluator must produce`() {
        assertEquals(5, table.size)
        assertEquals(
            listOf("0.20", "13.2", "8.8", "2.2", "13.2"),
            table.map { it.expectedResult },
        )
    }
}
