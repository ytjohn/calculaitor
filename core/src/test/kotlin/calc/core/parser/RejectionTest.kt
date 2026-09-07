package calc.core.parser

import calc.core.lexer.LexError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RejectionTest {

    private inline fun <reified E : Throwable> rejects(src: String): E =
        assertFailsWith<E>("expected '$src' to be rejected") { Parser.parse(src) }

    @Test
    fun `an unrecognised word rejects the line`() {
        val e = rejects<ParseError.Unexpected>("20% garbage on 11.49")
        assertIs<ParseError.Unexpected>(e)
    }

    @Test
    fun `a label word rejects the line`() {
        rejects<ParseError.Unexpected>("20% tip on 11.49")
    }

    @Test
    fun `an unknown trailing word rejects rather than being ignored`() {
        rejects<ParseError.Unexpected>("11 + 20% wibble")
        rejects<ParseError.Unexpected>("5 km frobnicate")
    }

    @Test
    fun `a recognised but unimplemented shape is Unsupported, not Unexpected`() {
        val e = rejects<ParseError.Unsupported>("5 miles at 17mph")
        assertIs<ParseError.Unsupported>(e)
    }

    @Test
    fun `Unsupported and Unexpected do not subsume one another`() {
        val unsupported = rejects<ParseError>("5 miles at 17mph")
        val unexpected = rejects<ParseError>("20% garbage on 11.49")

        assertIs<ParseError.Unsupported>(unsupported)
        assertIs<ParseError.Unexpected>(unexpected)
        assertNotEquals(unsupported::class.qualifiedName, unexpected::class.qualifiedName)
    }

    @Test
    fun `a dangling operator rejects`() {
        rejects<ParseError.Unexpected>("5 +")
        rejects<ParseError.Unexpected>("* 5")
    }

    @Test
    fun `an unclosed paren rejects`() {
        rejects<ParseError.Expected>("(1 + 2")
    }

    @Test
    fun `a conversion with no target rejects`() {
        rejects<ParseError.Expected>("50 USD in")
    }

    @Test
    fun `a currency symbol with no amount rejects`() {
        rejects<ParseError.Expected>("\$ + 1")
    }

    @Test
    fun `an unknown scale word after a currency amount rejects`() {
        rejects<ParseError.Unexpected>("\$1.2 gazillion")
    }

    @Test
    fun `a percent sign after a currency amount rejects`() {
        rejects<ParseError.Unexpected>("\$50%")
    }

    @Test
    fun `an unrecognised character is a lex error`() {
        rejects<LexError>("1 @ 2")
    }

    @Test
    fun `nothing partial escapes a rejected line`() {
        for (src in listOf("20% garbage on 11.49", "5 +", "(1 + 2", "1 @ 2")) {
            assertFailsWith<Exception>("'$src' must not yield a statement") { Parser.parse(src) }
        }
    }
}

import kotlin.test.assertNotEquals
