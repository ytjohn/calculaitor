# CLAUDE.md

Project context for Claude Code sessions. Read this before changing anything in `core/`.

## What this is

An Android notebook-style calculator in the spirit of Numi, Soulver, and CalcNote:
a text editor where every line evaluates and the result appears on the right.
Units, currencies, percentages, line references, and named variables.

The long-term goal is natural-language input. The eventual pipeline:

```
Compose UI
  -> deterministic lexer + parser        (handles almost everything)
  -> tiny local LLM, on parse failure    (structure only, never arithmetic)
  -> constrained AST
  -> evaluator / units / dates
  -> answer
```

**v1 ships without the model.** The deterministic engine is the product; the
normalizer is an enhancement to the failure path. Do not add model code, llama.cpp
bindings, or GGUF assets to this repo yet.

## Invariants

These are settled decisions with reasons. If a change requires breaking one, stop
and raise it rather than working around it.

### 1. Quantity magnitudes are always stored in base units

`5 mi` is stored as `8046.72` with `dimension = LENGTH` and `preferredUnit = mi`.
Base units are metre, kilogram, second, bit, kelvin, radian.

Arithmetic therefore never converts, and the unit is purely a display hint. This is
what keeps the evaluator small. It is also the most expensive decision to reverse,
so do not introduce a code path that stores magnitudes in their source unit.

### 2. `Percent` stays unresolved until an operator sees it

`20%` is not `0.20`. The operator decides:

| Input | Meaning |
| --- | --- |
| `20%` alone | 0.20 |
| `11 + 20%` | 11 x 1.20 = 13.2 |
| `11 - 20%` | 11 x 0.80 |
| `20% of 11` | 11 x 0.20 = 2.2 |
| `20% on 11` | 11 x 1.20 |

Collapsing `Percent` to a scalar at parse time destroys this permanently. It must
survive as its own `Value` type into the evaluator.

### 3. `of` and `on` are different operators

`37% of 1 million` is 370,000. `20% on 11.49` is 13.79. Numi conflates them; we do
not. They parse to `BinOp.PERCENT_OF` and `BinOp.PERCENT_ON` respectively.

### 4. The skip-word set is closed. Never make it open-ended

Numi drops any token it does not recognise, which is why `20% garbage on 11.49`
confidently returns 13.79. A typo or a genuinely meaning-changing word produces a
wrong answer with no signal.

Here, only words in `SKIP_WORDS` are dropped. Every other unrecognised word becomes
a token that fails to resolve, and the line yields **no result** rather than a wrong
one. Grow the list deliberately, one word at a time, with a test for each.

A line that produces no result is correct behaviour, not a bug to be fixed.

### 5. `Unsupported` and `Unexpected` are different errors

- `ParseError.Unexpected` — genuine malformed input. `20% garbage on 11.49`.
- `ParseError.Unsupported` — recognised shape we do not implement yet.
  `5 miles at 17mph`.

`Unsupported` is the exact input class the future normalizer will handle. Keeping it
distinct now means the seam already exists. Do not merge these.

### 6. Currency is not a dimension

USD and GBP are not interconvertible without a runtime rate, so `Money` is a separate
`Value` type, not a `Quantity` with a currency dimension. Conversion goes through an
injected `RateProvider` so that `core` stays pure and offline-testable.

### 7. Prefer dimensional analysis over named functions

`5 mi / 17 mph` is a time. `350 MB / 8 Mbps` is a time. Both fall out of dimensional
division; neither needs a `duration(...)` function.

Every named function widens the grammar, adds a way to choose wrong, and needs its own
test and (later) training coverage. A dozen primitives plus a correct unit system beats
fifty domain functions. Before adding a function, check whether the unit algebra
already produces the answer.

### 8. `core` has zero Android dependencies

Everything in `core/` is plain Kotlin, JVM-testable, no emulator. This is deliberate:

- it is the part that can be worked on autonomously in cloud sessions
- it builds against Maven Central with no Android SDK and no allowlist changes
- it keeps the engine testable without a device

No `android.*` imports, no `Context`, no resources. If something needs Android, it
belongs in `data/` or `app/`.

### 9. Reject rather than repair

If input does not parse, produce nothing. Never guess at intent, never silently
correct, never fall back to a looser parse. The value of this product is that a
displayed number is trustworthy.

### 10. When the model arrives, it emits structure only

Recorded now so it is not relitigated later. The normalizer will emit a constrained
AST and nothing else. It must not resolve:

- AM/PM (`3:15` stays ambiguous; the evaluator resolves against context)
- currency for a bare number (locale default applies at eval time)
- anything requiring repeating an operand in two places

Every semantic decision delegated to a sub-1B model is a place it can be quietly
wrong. Structure is checkable; semantics is not.

## Repository layout

```
core/
  model/      Dimension, UnitDef, Quantity, Money, Percent
  units/      UnitRegistry, CurrencyRegistry
  lexer/      Token, Lexer, SKIP_WORDS, MAGNITUDE_WORDS
  parser/     Ast, Parser (Pratt)
  eval/       evaluator, sheet DAG, RateProvider          [not written yet]
nl/
  contract/   Normalizer interface + NoOpNormalizer       [not written yet]
data/         sheet persistence, FX rate cache            [not written yet]
app/          Compose notebook UI                         [not written yet]
```

## Parser notes

Pratt parser. Binding powers, loosest first:

| Level | Value | Operators |
| --- | --- | --- |
| convert | 5 | `in`, `to`, `as` |
| additive | 10 | `+`, `-` |
| percent-relative | 15 | `of`, `on` |
| multiplicative | 20 | `*`, `/`, `per` |
| unary | 25 | prefix `-` |
| power | 30 | `^` (right-associative) |

`of` sits between additive and multiplicative so `20% of 100 + 5` reads as
`(20% of 100) + 5`.

Unit suffixes bind tighter than every operator and are handled in `parseNumberTail`,
not as an infix case.

The parser is context-free. `Ident` resolution — variable vs unit vs undefined — is
the evaluator's job, because it needs the sheet scope.

## UI behaviour that matters

Two things create the "notebook" feeling. Neither is cosmetic:

1. **Recompute is incremental and instant on every keystroke.** Not on blur, not
   debounced beyond a frame.
2. **An unparseable line shows nothing at all** — no error marker, no red squiggle.
   It just reads as a comment.

Additionally, once the normalizer exists, the resolved interpretation must be shown
alongside the answer (`45 mi / 60 mph = 45 min`). A well-formed but wrong AST is the
failure mode a grammar cannot catch; showing the interpretation is the only defence.

## Testing

- Golden-file tests: `input -> expected canonical AST`. Compare normalised ASTs, never
  rendered strings.
- Every entry added to `SKIP_WORDS` gets a test.
- Property tests for the unit system: round-trip conversion, dimensional consistency
  under multiply/divide, associativity where it should hold.
- Percent semantics get an explicit table test — all five cases from invariant 2.
- When the normalizer eventually exists, its eval set must be **hand-written before
  the synthetic generator is built**, and must never share a generator with training
  data. Otherwise the score measures the generator, not the model.

## Open questions

Not yet decided. Raise rather than resolve unilaterally.

- **Label words.** `20% tip on 11.49` currently rejects because `tip` is unknown.
  Leading proposal: allow one unrecognised word in leading or trailing position only,
  never between an operator and its operand. Not implemented.
- Line reference syntax (`line3`? `L3`? implicit previous-line?).
- Whether `MB` is 10^6 or 2^20 bytes, and how to express the other.
- Temperature: affine units break the simple `toBase` multiply. `UnitDef.offsetToBase`
  exists but nothing uses it.

## Build

```bash
./gradlew :core:test          # JVM only, no Android SDK required
./gradlew :app:assembleDebug  # needs Android SDK
```

Cloud sessions: `core` builds and tests with the preinstalled Java 21 and default
network access. Android modules need an SDK setup script and `dl.google.com` on the
allowlist — prefer keeping cloud work inside `core`.

## Conventions

- Kotlin, `BigDecimal` throughout with the shared `MC` MathContext. No `Double` in
  `core`; predictable rounding is a product requirement.
- Comments explain *why*, not what. The invariants above are the kind of thing worth
  a comment at the call site.
- Prefer widening test coverage over widening the grammar.
