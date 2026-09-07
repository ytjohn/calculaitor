package calc.core.parser

import calc.core.model.Money
import calc.core.model.Percent
import calc.core.model.Quantity
import calc.core.model.Value
import java.math.BigDecimal

/**
 * Test support for golden AST comparison.
 *
 * Golden tests compare normalised ASTs, never rendered strings: a renderer is a
 * display concern that will change, and a string comparison would fail on
 * formatting churn while passing on a genuinely wrong tree.
 *
 * Normalisation strips BigDecimal scale and nothing else. `1.2 million` evaluates
 * to 1200000.0 (scale 1) and `1200000` to 1200000 (scale 0); BigDecimal.equals is
 * scale-sensitive, so without this every magnitude-word test would depend on the
 * incidental scale of the multiply. The tree shape itself is left untouched.
 */
fun Expr.normalised(): Expr = when (this) {
    is Literal -> Literal(value.normalised())
    is UnitApply -> UnitApply(magnitude.normalised(), unitName)
    is Binary -> Binary(op, left.normalised(), right.normalised())
    is Unary -> Unary(op, operand.normalised())
    is Convert -> Convert(expr.normalised(), targetName)
    is Ident, is LineRef -> this
}

fun Value.normalised(): Value = when (this) {
    is Quantity -> copy(magnitude = magnitude.stripTrailingZeros())
    is Money -> copy(amount = amount.stripTrailingZeros())
    is Percent -> copy(fraction = fraction.stripTrailingZeros())
}

fun Statement.normalised(): Statement = when (this) {
    is Statement.Expression -> Statement.Expression(expr.normalised())
    is Statement.Assignment -> Statement.Assignment(name, expr.normalised())
    Statement.Blank -> Statement.Blank
}

// --- Expected-AST builders, so golden tests read close to the input they describe. ---

/** A bare scalar literal. */
fun n(v: String): Expr = Literal(Quantity.scalar(BigDecimal(v))).normalised()

/** A percent literal written in percentage points: `pct("20")` is `20%`. */
fun pct(points: String): Expr = Literal(Percent.ofPercentagePoints(BigDecimal(points))).normalised()

/** A magnitude with a unit name as written; the name is resolved by the evaluator, not here. */
fun q(v: String, unit: String): Expr = UnitApply(n(v), unit)

/** Parse and normalise, asserting the line is an expression rather than an assignment. */
fun exprOf(src: String): Expr =
    (Parser.parse(src) as Statement.Expression).expr.normalised()
