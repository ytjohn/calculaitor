package calc.core.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Shared precision for all arithmetic. Wide enough that display rounding is the only rounding. */
val MC: MathContext = MathContext(34, RoundingMode.HALF_UP)

/**
 * Base physical dimensions.
 *
 * Currency is deliberately absent: USD and GBP are not interconvertible without a
 * runtime rate, so money is a separate Value type with its own conversion path.
 */
enum class BaseDimension { LENGTH, MASS, TIME, INFORMATION, TEMPERATURE, ANGLE, CURRENT, SUBSTANCE, LUMINOSITY }

/**
 * A dimension is a vector of exponents over the base dimensions.
 * Speed is LENGTH^1 TIME^-1; area is LENGTH^2; a scalar is the empty map.
 *
 * Always construct through [of] / [base] so the zero-exponent normalisation holds;
 * without it, LENGTH^0 and the empty map would compare unequal.
 */
data class Dimension(val exponents: Map<BaseDimension, Int>) {

    val isScalar: Boolean get() = exponents.isEmpty()

    operator fun times(other: Dimension): Dimension = combine(other, 1)
    operator fun div(other: Dimension): Dimension = combine(other, -1)

    fun pow(n: Int): Dimension = of(exponents.mapValues { it.value * n })

    private fun combine(other: Dimension, sign: Int): Dimension {
        val out = exponents.toMutableMap()
        for ((dim, exp) in other.exponents) out[dim] = (out[dim] ?: 0) + sign * exp
        return of(out)
    }

    override fun toString(): String =
        if (isScalar) "1"
        else exponents.entries.sortedBy { it.key.name }
            .joinToString("·") { (d, e) -> if (e == 1) d.name else "${d.name}^$e" }

    companion object {
        val SCALAR = Dimension(emptyMap())

        fun of(map: Map<BaseDimension, Int>) = Dimension(map.filterValues { it != 0 })
        fun of(vararg pairs: Pair<BaseDimension, Int>) = of(pairs.toMap())
        fun base(d: BaseDimension) = Dimension(mapOf(d to 1))
    }
}

/**
 * A named unit. [toBase] converts a magnitude in this unit to the canonical base
 * unit of its dimension (metres, kilograms, seconds, bits, kelvin, radians).
 *
 * [offsetToBase] exists only for the affine temperature scales: base = value * toBase + offset.
 * It is the reason temperature cannot be treated as an ordinary scaling unit — see UnitRegistry.
 */
data class UnitDef(
    val symbol: String,
    val dimension: Dimension,
    val toBase: BigDecimal,
    val offsetToBase: BigDecimal = BigDecimal.ZERO,
    val aliases: Set<String> = emptySet(),
) {
    val isAffine: Boolean get() = offsetToBase.signum() != 0
    override fun toString(): String = symbol
}

data class Currency(val code: String, val symbol: String? = null, val minorUnits: Int = 2) {
    override fun toString(): String = code
}

/** Anything a line can evaluate to. */
sealed interface Value

/**
 * A dimensioned (or scalar) number.
 *
 * INVARIANT: [magnitude] is always in base units. `5 mi` is stored as 8046.72 with
 * dimension LENGTH and preferredUnit = mi. Arithmetic therefore never converts, and
 * the unit survives only as a display hint. This is the single decision that keeps
 * the evaluator small.
 */
data class Quantity(
    val magnitude: BigDecimal,
    val dimension: Dimension = Dimension.SCALAR,
    val preferredUnit: UnitDef? = null,
) : Value {
    val isScalar: Boolean get() = dimension.isScalar

    companion object {
        fun scalar(v: BigDecimal) = Quantity(v, Dimension.SCALAR)
        fun scalar(v: Long) = scalar(BigDecimal.valueOf(v))
    }
}

/** Money is not a Quantity: conversion needs a RateProvider, so it stays a distinct type. */
data class Money(val amount: BigDecimal, val currency: Currency) : Value

/**
 * A percentage, held unresolved.
 *
 * `20%` alone is 0.20, but `11 + 20%` is 13.2 and `20% of 11` is 2.2. The operator
 * decides, so Percent must survive as its own type until the evaluator sees the
 * operator applying to it. Collapsing it to 0.20 at parse time loses that.
 */
data class Percent(val fraction: BigDecimal) : Value {
    companion object {
        /** 20 -> 0.20 */
        fun ofPercentagePoints(points: BigDecimal) = Percent(points.divide(BigDecimal(100), MC))
    }
}
