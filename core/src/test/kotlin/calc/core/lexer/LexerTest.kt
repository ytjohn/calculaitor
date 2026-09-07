package calc.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.math.BigDecimal

private fun lex(src: String): List<Token> = Lexer(src).tokenize()

/** Token types in order, EOF dropped — enough to assert shape without asserting positions. */
private fun types(src: String): List<TokenType> = lex(src).dropLast(1).map { it.type }

private fun texts(src: String): List<String> = lex(src).dropLast(1).map { it.text }

class LexerTest {

    @Test
    fun `emits EOF for empty input`() {
        assertEquals(listOf(TokenType.EOF), lex("").map { it.type })
        assertEquals(listOf(TokenType.EOF), lex("   \t ").map { it.type })
    }

    @Test
    fun `numbers carry a BigDecimal value`() {
        assertEquals(BigDecimal("42"), lex("42").first().number)
        assertEquals(BigDecimal("3.5"), lex("3.5").first().number)
        assertEquals(BigDecimal(".5"), lex(".5").first().number)
    }

    @Test
    fun `digit separators are stripped from numbers`() {
        assertEquals(BigDecimal("1234.5"), lex("1,234.5").first().number)
        assertEquals(BigDecimal("1000000"), lex("1_000_000").first().number)
    }

    @Test
    fun `a trailing dot is not part of the number`() {
        // `5.` must not lex as 5.0 with a dangling dot swallowed: the dot is only
        // consumed when a digit follows it.
        assertFailsWith<LexError> { lex("5.") }
    }

    @Test
    fun `unicode multiply and divide normalise to ASCII`() {
        assertEquals(listOf("3", "*", "4"), texts("3 × 4"))
        assertEquals(listOf("8", "/", "2"), texts("8 ÷ 2"))
    }

    @Test
    fun `currency symbols lex as words for the parser to attach`() {
        assertEquals(listOf(TokenType.WORD, TokenType.NUMBER), types("\$50"))
        assertEquals(listOf("£", "50"), texts("£50"))
    }

    @Test
    fun `an unrecognised character is a lex error, not a dropped token`() {
        val e = assertFailsWith<LexError> { lex("1 @ 2") }
        assertEquals(2, e.pos)
    }

    @Test
    fun `keywords are distinguished from ordinary words`() {
        assertEquals(Keyword.OF, lex("of").first().keyword)
        assertEquals(Keyword.ON, lex("on").first().keyword)
        assertEquals(Keyword.AT, lex("at").first().keyword)
        assertNull(lex("tip").first().keyword)
        assertEquals(TokenType.WORD, lex("tip").first().type)
    }

    @Test
    fun `keyword matching is case-insensitive but preserves the text as written`() {
        val t = lex("OF").first()
        assertEquals(Keyword.OF, t.keyword)
        assertEquals("OF", t.text)
    }

    // --- Invariant 4: the skip set is closed. ---

    @Test
    fun `an unrecognised word survives as a token rather than being dropped`() {
        // This is the whole discipline. Numi drops what it does not recognise, so
        // `20% garbage on 11.49` confidently returns 13.79. Here `garbage` survives
        // and goes on to fail in the parser.
        assertEquals(listOf("20", "%", "garbage", "on", "11.49"), texts("20% garbage on 11.49"))
    }

    @Test
    fun `skip words are dropped`() {
        assertEquals(listOf("20", "%", "of", "80"), texts("what is 20% of 80"))
        assertEquals(listOf("5", "3"), texts("5 and then 3"))
    }

    @Test
    fun `skip words are dropped regardless of case`() {
        assertEquals(emptyList<String>(), texts("The"))
        assertEquals(emptyList<String>(), texts("PLEASE"))
    }

    /**
     * Every entry in SKIP_WORDS gets a test (see CLAUDE.md, Testing).
     *
     * The table below is the test. `skip word table covers the whole set` fails the
     * build when a word is added to SKIP_WORDS without being added here, which is
     * what makes "grow the list deliberately, one word at a time" enforceable rather
     * than aspirational.
     */
    private val skipWordCases: Map<String, Pair<String, List<String>>> = mapOf(
        "the" to ("the 5 + 3" to listOf("5", "+", "3")),
        "a" to ("a 5 + 3" to listOf("5", "+", "3")),
        "an" to ("an 8 * 2" to listOf("8", "*", "2")),
        "is" to ("what is 20% of 80" to listOf("20", "%", "of", "80")),
        "are" to ("are 5 + 3" to listOf("5", "+", "3")),
        "was" to ("was 5 + 3" to listOf("5", "+", "3")),
        "be" to ("be 5 + 3" to listOf("5", "+", "3")),
        "and" to ("5 and 3" to listOf("5", "3")),
        "then" to ("5 then 3" to listOf("5", "3")),
        "please" to ("please 5 + 3" to listOf("5", "+", "3")),
        "just" to ("just 5 + 3" to listOf("5", "+", "3")),
        "what" to ("what 20% of 80" to listOf("20", "%", "of", "80")),
        "whats" to ("whats 20% of 80" to listOf("20", "%", "of", "80")),
        "how" to ("how much is 5 + 3" to listOf("5", "+", "3")),
        "much" to ("how much is 5 + 3" to listOf("5", "+", "3")),
        "many" to ("how many 5 + 3" to listOf("5", "+", "3")),
    )

    @Test
    fun `skip word table covers the whole set`() {
        assertEquals(SKIP_WORDS, skipWordCases.keys, "every SKIP_WORDS entry needs a case here")
    }

    @Test
    fun `each skip word is dropped in context`() {
        for ((word, case) in skipWordCases) {
            val (input, expected) = case
            assertTrue(word in input.lowercase(), "case for '$word' must actually contain it")
            assertEquals(expected, texts(input), "skip word '$word'")
        }
    }

    @Test
    fun `magnitude word table covers the whole set`() {
        assertEquals(
            setOf("hundred", "thousand", "million", "billion", "trillion"),
            MAGNITUDE_WORDS.keys,
        )
    }

    @Test
    fun `magnitude words stay ordinary word tokens for the parser to fold`() {
        // The lexer does not multiply; parseNumberTail does, because folding needs
        // to know a number came immediately before.
        assertEquals(listOf(TokenType.NUMBER, TokenType.WORD), types("1.2 million"))
    }
}
