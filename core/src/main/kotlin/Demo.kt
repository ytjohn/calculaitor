package calc.core

import calc.core.lexer.LexError
import calc.core.parser.ParseError
import calc.core.parser.Parser
import calc.core.parser.Statement
import calc.core.parser.render

private val LINES = listOf(
    "11 + 20%",
    "5 miles at 17mph",
    "5 mi / 17mph",
    "20% tip on 11.49",
    "20% on 11.49",
    "20% garbage on 11.49",
    "50 USD in GBP",
    "37% of 1 million",
    "37% of 1.2 million",
    "total = 5 km + 300 m",
    "what is 20% of 80",
    "350 MB / 8 Mbps",
)

fun main() {
    val w = LINES.maxOf { it.length }
    for (line in LINES) {
        val result = try {
            when (val s = Parser.parse(line)) {
                is Statement.Expression -> s.expr.render()
                is Statement.Assignment -> "${s.name} := ${s.expr.render()}"
                Statement.Blank -> ""
            }
        } catch (e: ParseError.Unsupported) {
            "-- unsupported (route to normalizer): ${e.message}"
        } catch (e: ParseError) {
            "-- rejected: ${e.message}"
        } catch (e: LexError) {
            "-- rejected: ${e.message}"
        }
        println(line.padEnd(w) + "   " + result)
    }
}
