package calc.core.units

import calc.core.model.BaseDimension
import calc.core.model.BaseDimension.INFORMATION
import calc.core.model.BaseDimension.LENGTH
import calc.core.model.BaseDimension.TIME
import calc.core.model.Dimension
import calc.core.model.MC
import calc.core.model.UnitDef
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnitRegistryTest {

    @Test
    fun `symbols resolve`() {
        assertEquals("mi", UnitRegistry.find("mi")?.symbol)
        assertEquals("mph", UnitRegistry.find("mph")?.symbol)
    }

    @Test
    fun `aliases resolve to the canonical unit`() {
        for (alias in listOf("mile", "miles")) {
            assertSame(UnitRegistry.find("mi"), UnitRegistry.find(alias), "alias '$alias'")
        }
        assertSame(UnitRegistry.find("h"), UnitRegistry.find("hours"))
        assertSame(UnitRegistry.find("m"), UnitRegistry.find("metres"))
    }

    @Test
    fun `every alias in the registry is reachable`() {
        for (u in UnitRegistry.units) {
            for (a in u.aliases) assertSame(u, UnitRegistry.find(a), "${u.symbol} alias '$a'")
            assertSame(u, UnitRegistry.find(u.symbol), "symbol '${u.symbol}'")
        }
    }

    @Test
    fun `case-sensitive symbols win over the case-insensitive fallback`() {
        assertEquals(BigDecimal("1"), UnitRegistry.find("b")?.toBase)
        assertEquals(BigDecimal("8"), UnitRegistry.find("B")?.toBase)
        assertNotEquals(UnitRegistry.find("b"), UnitRegistry.find("B"))
        assertEquals("MB", UnitRegistry.find("mb")?.symbol)
    }

    @Test
    fun `an unknown name resolves to nothing`() {
        assertNull(UnitRegistry.find("garbage"))
        assertNull(UnitRegistry.find("tip"))
        assertEquals(false, UnitRegistry.isUnit("furlong"))
    }

    @Test
    fun `no registered unit is affine yet`() {
        val affine = UnitRegistry.units.filter { it.isAffine }
        assertTrue(affine.isEmpty(), "affine units need offset-aware conversion: $affine")
    }
}

class CurrencyRegistryTest {

    @Test
    fun `codes resolve case-insensitively`() {
        assertEquals("USD", CurrencyRegistry.find("usd")?.code)
        assertEquals("GBP", CurrencyRegistry.find("GBP")?.code)
    }

    @Test
    fun `symbols are not codes`() {
        assertNull(CurrencyRegistry.find("$"))
    }

    @Test
    fun `JPY has no minor units`() {
        assertEquals(0, CurrencyRegistry.find("JPY")?.minorUnits)
        assertEquals(2, CurrencyRegistry.find("USD")?.minorUnits)
    }

    @Test
    fun `currency is not a dimension`() {
        assertTrue(BaseDimension.entries.none { it.name.contains("CURRENC") })
    }
}

class UnitSystemPropertyTest {

    private val rng = java.util.Random(20260906)
    private val units = UnitRegistry.units

    private fun randomMagnitude(): BigDecimal =
        BigDecimal(rng.nextInt(1_000_000) + 1).movePointLeft(rng.nextInt(6))

    private val tol = BigDecimal("1E-26")

    private fun assertClose(expected: BigDecimal, actual: BigDecimal, what: String) {
        if (expected.compareTo(actual) == 0) return
        val error = (actual - expected).abs().divide(expected.abs().max(BigDecimal.ONE), MC)
        assertTrue(error < tol, "$what: expected ~$expected, got $actual (relative error $error)")
    }

    @Test
    fun `conversion to base and back round-trips`() {
        repeat(200) {
            val u = units[rng.nextInt(units.size)]
            val v = randomMagnitude()
            val base = v.multiply(u.toBase, MC)
            val back = base.divide(u.toBase, MC)
            assertClose(v, back, "round-trip through ${u.symbol}")
        }
    }

    @Test
    fun `conversion between two units of the same dimension round-trips`() {
        val byDimension = units.groupBy { it.dimension }
        repeat(200) {
            val group = byDimension.values.toList()[rng.nextInt(byDimension.size)]
            val from = group[rng.nextInt(group.size)]
            val to = group[rng.nextInt(group.size)]
            val v = randomMagnitude()

            val stored = v.multiply(from.toBase, MC)
            val shown = stored.divide(to.toBase, MC)
            val backToBase = shown.multiply(to.toBase, MC)

            assertClose(stored, backToBase, "${from.symbol} -> ${to.symbol}")
        }
    }

    @Test
    fun `dimensions are consistent under multiply and divide`() {
        repeat(300) {
            val a = units[rng.nextInt(units.size)].dimension
            val b = units[rng.nextInt(units.size)].dimension

            assertEquals(a, (a * b) / b, "(a*b)/b")
            assertEquals(a, (a / b) * b, "(a/b)*b")
            assertEquals(Dimension.SCALAR, a / a, "a/a")
            assertEquals(a * b, b * a, "commutativity")
        }
    }

    @Test
    fun `dimension multiplication is associative`() {
        repeat(300) {
            val a = units[rng.nextInt(units.size)].dimension
            val b = units[rng.nextInt(units.size)].dimension
            val c = units[rng.nextInt(units.size)].dimension
            assertEquals((a * b) * c, a * (b * c))
        }
    }

    @Test
    fun `powers agree with repeated multiplication`() {
        repeat(100) {
            val d = units[rng.nextInt(units.size)].dimension
            assertEquals(Dimension.SCALAR, d.pow(0))
            assertEquals(d, d.pow(1))
            assertEquals(d * d, d.pow(2))
            assertEquals(d * d * d, d.pow(3))
            assertEquals(Dimension.SCALAR, d.pow(-1) * d)
        }
    }

    @Test
    fun `zero exponents normalise away`() {
        assertEquals(Dimension.SCALAR, Dimension.of(LENGTH to 0))
        assertEquals(Dimension.base(LENGTH), Dimension.of(LENGTH to 1, TIME to 0))
    }
}

class DimensionalAnalysisTest {

    private fun dim(name: String): Dimension =
        requireNotNull(UnitRegistry.find(name)) { "no unit '$name'" }.dimension

    @Test
    fun `length divided by speed is a time`() {
        assertEquals(Dimension.base(TIME), dim("mi") / dim("mph"))
        assertEquals(Dimension.base(TIME), dim("km") / dim("kph"))
    }

    @Test
    fun `information divided by transfer rate is a time`() {
        assertEquals(Dimension.base(TIME), dim("MB") / dim("Mbps"))
    }

    @Test
    fun `speed is length over time however it is spelled`() {
        assertEquals(dim("mph"), dim("mi") / dim("h"))
        assertEquals(dim("kph"), dim("km") / dim("h"))
        assertEquals(dim("Mbps"), dim("MB") / dim("s"))
    }

    @Test
    fun `length times length is an area`() {
        assertEquals(Dimension.of(LENGTH to 2), dim("m") * dim("m"))
    }

    @Test
    fun `mixing dimensions produces a distinct dimension, not a scalar`() {
        val energyish: Dimension = Dimension.of(INFORMATION to 1, TIME to -1)
        assertEquals(dim("Mbps"), energyish)
        assertNotEquals(Dimension.SCALAR, energyish)
    }
}

class BaseUnitStorageTest {

    private fun toBase(v: String, unit: String): BigDecimal {
        val u: UnitDef = requireNotNull(UnitRegistry.find(unit))
        return BigDecimal(v).multiply(u.toBase, MC)
    }

    @Test
    fun `5 mi is stored as 8046 point 72 metres`() {
        assertEquals(0, BigDecimal("8046.720").compareTo(toBase("5", "mi")))
    }

    @Test
    fun `equal lengths in different units store identically`() {
        assertEquals(0, toBase("1", "km").compareTo(toBase("1000", "m")))
        assertEquals(0, toBase("1", "m").compareTo(toBase("100", "cm")))
        assertEquals(0, toBase("1", "h").compareTo(toBase("60", "min")))
        assertEquals(0, toBase("1", "B").compareTo(toBase("8", "b")))
    }

    @Test
    fun `arithmetic on stored magnitudes needs no conversion`() {
        val sum = toBase("5", "km") + toBase("300", "m")
        assertEquals(0, BigDecimal("5300").compareTo(sum))
    }
}
