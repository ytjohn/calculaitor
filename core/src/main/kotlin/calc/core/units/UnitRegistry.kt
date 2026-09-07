package calc.core.units

import calc.core.model.BaseDimension.*
import calc.core.model.Currency
import calc.core.model.Dimension
import calc.core.model.UnitDef
import java.math.BigDecimal

private fun bd(s: String) = BigDecimal(s)

private val LENGTH_D = Dimension.base(LENGTH)
private val TIME_D = Dimension.base(TIME)
private val INFO_D = Dimension.base(INFORMATION)
private val SPEED_D = Dimension.of(LENGTH to 1, TIME to -1)
private val RATE_D = Dimension.of(INFORMATION to 1, TIME to -1)

/**
 * The starter unit set.
 *
 * Compound units (mph, Mbps) are registered as single UnitDefs with a composite
 * dimension rather than parsed as `mi/h`. That keeps the lexer trivial; a later
 * pass can add real compound-unit syntax without changing anything downstream,
 * because both routes produce the same Dimension.
 */
object UnitRegistry {

    val units: List<UnitDef> = listOf(
        // length (base: metre)
        UnitDef("m", LENGTH_D, bd("1"), aliases = setOf("meter", "meters", "metre", "metres")),
        UnitDef("km", LENGTH_D, bd("1000"), aliases = setOf("kilometer", "kilometers")),
        UnitDef("cm", LENGTH_D, bd("0.01")),
        UnitDef("mi", LENGTH_D, bd("1609.344"), aliases = setOf("mile", "miles")),
        UnitDef("ft", LENGTH_D, bd("0.3048"), aliases = setOf("foot", "feet")),

        // time (base: second)
        UnitDef("s", TIME_D, bd("1"), aliases = setOf("sec", "secs", "second", "seconds")),
        UnitDef("min", TIME_D, bd("60"), aliases = setOf("minute", "minutes")),
        UnitDef("h", TIME_D, bd("3600"), aliases = setOf("hr", "hrs", "hour", "hours")),
        UnitDef("day", TIME_D, bd("86400"), aliases = setOf("days")),

        // speed
        UnitDef("mph", SPEED_D, bd("0.44704")),
        UnitDef("kph", SPEED_D, bd("0.277777777777777777777777777778"), aliases = setOf("kmh")),

        // information (base: bit)
        UnitDef("b", INFO_D, bd("1"), aliases = setOf("bit", "bits")),
        UnitDef("B", INFO_D, bd("8"), aliases = setOf("byte", "bytes")),
        UnitDef("MB", INFO_D, bd("8000000")),
        UnitDef("GB", INFO_D, bd("8000000000")),

        // transfer rate
        UnitDef("Mbps", RATE_D, bd("1000000")),
        UnitDef("MBps", RATE_D, bd("8000000")),
    )

    /**
     * Lookup is case-sensitive first, then case-insensitive. `MB` and `mb` must be
     * distinguishable from `Mb` eventually; resolving exact matches first leaves
     * room for that without breaking casual input today.
     */
    private val exact: Map<String, UnitDef> = buildMap {
        for (u in units) {
            put(u.symbol, u)
            for (a in u.aliases) put(a, u)
        }
    }

    private val lowered: Map<String, UnitDef> = buildMap {
        for (u in units) {
            putIfAbsent(u.symbol.lowercase(), u)
            for (a in u.aliases) putIfAbsent(a.lowercase(), u)
        }
    }

    fun find(name: String): UnitDef? = exact[name] ?: lowered[name.lowercase()]
    fun isUnit(name: String): Boolean = find(name) != null
}

object CurrencyRegistry {
    val currencies = listOf(
        Currency("USD", "$"),
        Currency("GBP", "£"),
        Currency("EUR", "€"),
        Currency("JPY", "¥", minorUnits = 0),
    )

    private val byCode = currencies.associateBy { it.code }
    fun find(code: String): Currency? = byCode[code.uppercase()]
    fun isCurrency(code: String): Boolean = find(code) != null
}
