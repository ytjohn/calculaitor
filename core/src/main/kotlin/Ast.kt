package calc.core.parser

import calc.core.model.Value

enum class BinOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), POW("^"),
    PERCENT_OF("of"),   // 37% of 1 million -> 370000
    PERCENT_ON("on"),   // 20% on 11.49     -> 13.788
}

enum class UnOp(val symbol: String) { NEGATE("-") }

sealed interface Expr

/** A resolved constant: a number, a percentage, a money amount. */
data class Literal(val value: Value) : Expr

/** `5 mi` — magnitude plus the unit name as written. Resolved by the evaluator, not the parser. */
data class UnitApply(val magnitude: Expr, val unitName: String) : Expr

/** A bare identifier. May be a variable, a unit name (in conversion position), or nothing. */
data class Ident(val name: String) : Expr

/** `line3`, or the implicit "previous line" reference. */
data class LineRef(val index: Int) : Expr

data class Binary(val op: BinOp, val left: Expr, val right: Expr) : Expr
data class Unary(val op: UnOp, val operand: Expr) : Expr

/** `50 USD in GBP` — the target is a name, never an expression. */
data class Convert(val expr: Expr, val targetName: String) : Expr

sealed interface Statement {
    /** A line that produces a result. */
    data class Expression(val expr: Expr) : Statement

    /** `total = 5 + 3` — binds a name for later lines. */
    data class Assignment(val name: String, val expr: Expr) : Statement

    /** Empty or whitespace-only. Renders as a blank row, no result. */
    data object Blank : Statement
}

/** Renders an AST back to a canonical string. Used by tests and by the interpretation echo in the UI. */
fun Expr.render(): String = when (this) {
    is Literal -> value.toString()
    is UnitApply -> "${magnitude.render()} $unitName"
    is Ident -> name
    is LineRef -> "line$index"
    is Unary -> "${op.symbol}${operand.render()}"
    is Binary -> "(${left.render()} ${op.symbol} ${right.render()})"
    is Convert -> "(${expr.render()} in $targetName)"
}
