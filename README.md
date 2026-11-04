# Freer

[![CI](https://github.com/BenjaminEarley/Freer/actions/workflows/ci.yml/badge.svg)](https://github.com/BenjaminEarley/Freer/actions/workflows/ci.yml)

Extensible effects for Kotlin Multiplatform, after [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) by Kiselyov and Ishii. A `Program` is an immutable value describing a computation and its effects; handlers applied afterwards decide what each effect does, so one flow runs against real services in production, scripted handlers in tests, a dry run or audit middleware, unchanged. Each effect is reified with its continuation, which makes a program replayable, interceptable and resumable more than once on every target, including Wasm, for one small allocation per effect.

API reference (KDoc): [benjaminearley.github.io/Freer](https://benjaminearley.github.io/Freer/).

## Installation

Add `implementation("com.benjaminearley:freer:0.2.1")` to a common source set or any JVM module. Targets: JVM, Wasm, macOS, iOS, Linux and Windows. Everything is common code except `ioBlocking()`, which is JVM-only.

## Quick Example

A sealed interface declares an effect family, a program performs its effects, a handler answers them. One program, a scripted handler for the test, a real one for production:

```kotlin
import com.benjaminearley.freer.*

sealed interface Console<out R> : Effect<R>
data class Print(val line: String) : Console<Unit>
data object ReadLine : Console<String>

val greeter: Program<String> = program {
    perform(Print("What is your name?")).bind()
    val name = perform(ReadLine).bind()
    perform(Print("Hello, $name!")).bind()
    name
}

fun <A> Program<A>.console(): Program<A> = handle<Console<*>, A> { op ->
    when (op) {
        is Print -> println(op.line)
        ReadLine -> readlnOrNull() ?: "stranger"
    }
}

fun <A> Program<A>.scripted(input: String, printed: MutableList<String>): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> { printed += op.line }
            ReadLine -> input
        }
    }

fun main() {
    val printed = mutableListOf<String>()
    val name = greeter.scripted("Ada", printed).runOrThrow()
    check(name == "Ada" && printed == listOf("What is your name?", "Hello, Ada!"))
    greeter.console().runOrThrow()
}
```

CI compiles and runs this block.

## Core Concepts

`perform(effect)` is a one-step program that suspends on the effect. `program { }` sequences programs with `.bind()`, as `flatMap` does without a block. `runOrThrow()` and `run()` extract the value once every effect is handled. A handler is applied lazily, dispatches on the erased effect class (match the star-projected family, as the example does) and removes the effects it answers. Five forms:

| Form | The rule receives | Use it for |
| --- | --- | --- |
| `handle` | the effect; its return value is the response | most handlers |
| `handleS` | the state and the effect; returns `newState to response`; seeded by its argument | handler-local state |
| `intercept` | the effect and `proceed()`, which re-emits it downstream | middleware: audit, retry, rewrite |
| `interpret` | the effect and `resume`, the rest of the program as a value | abort, multi-shot, generators |
| `interpretS` | the state, the effect and `resume(state, response)` | stateful control flow |

Built-in effects, in `com.benjaminearley.freer.effects`: `State`, `Reader`, `Failure` (`raise`, `attempt`, `recover`), `NonDet` (multi-shot `choose`, `msplit`, `once`, `ifte`), generators and `IO` (`performIO`, run at the edge by `io()` or `ioBlocking()`). Their KDoc says what each does, how to order them and what is not type-checked.

## Semantics

The interpreter is checked against a specification: a tree interpreter transcribing the paper's [Eff1.hs](https://okmij.org/ftp/Haskell/extensible/Eff1.hs) in `freer/src/commonTest/kotlin/com/benjaminearley/freer/reference/`. The paper's worked examples run as golden tests through both (`PaperExamplesTest.kt`), and the monad, state, reader, nondeterminism and handler-order laws run as [kotest](https://kotest.io/docs/proptest/property-based-testing.html) properties over random programs under random handler stacks on every target (`laws/`); the library must agree with the model (`DifferentialTest.kt`). The laws follow [Wadler](https://homepages.inf.ed.ac.uk/wadler/topics/monads.html), [Gibbons and Hinze](https://www.cs.ox.ac.uk/jeremy.gibbons/publications/mr.pdf), [Plotkin and Pretnar](https://lmcs.episciences.org/705) and [LogicT](https://okmij.org/ftp/Computation/LogicT.pdf). One program may be interpreted from many threads at once (`ConcurrentInterpretationTest.kt`, and `jcstress/` on the JVM). [Pitest](https://pitest.org) mutation testing gates CI at the score set in `freer/build.gradle.kts`, where the surviving mutants are classified.

## Performance

Per effect, with JMH on the v0.2.0 code, JDK 21, Apple silicon (`freer/src/jvmBenchmark/`): `handle` costs 8.6 ns and 72 B, `interpret` 8.4 ns and 72 B, `intercept` then `handle` 64 ns and 472 B, and a raw suspend loop 4.3 ns and 0 B; JDK 17 allocates 144 B for `handle`. The gap to the raw loop is the reified continuation, which [evidence passing](https://doi.org/10.1145/3473576) and [runtime continuations](https://arxiv.org/abs/2104.00250) avoid in the tail-resumptive case, and which buys replay, interception and multi-shot resumption. Bind chains are linear in either association (`BindChainBenchmark.kt`). A hundred thousand effects through stacked handlers, recursion and multi-shot searches run on a constant stack (`TrampolineScenarios.kt`, on a 256 KB stack in `SmallStackTest.kt`); the one stack bound is the number of nested handlers, three JVM frames per level (`StackDepthTest.kt`, `NestingDepthTest.kt`).

## Project layout

- `freer/` is the library: common code in `src/commonMain/`, JVM-only code in `src/jvmMain/`, Wasm and native in `src/nonJvmMain/`, tests in `src/commonTest/` (`reference/`, `laws/`) and `src/jvmTest/`, JMH in `src/jvmBenchmark/`, API dumps in `api/`.
- `examples/` is a JVM demo of middleware, async I/O, error handling, generators, nondeterminism and transactions (`./gradlew :examples:run`).
- `jcstress/` stress-tests shared programs across threads.
- `.github/workflows/` runs CI on Linux, macOS and Windows, publishes the API reference and releases from tags.

## Contributing / Releasing

```sh
./gradlew check                 # tests, API check, README checks
./gradlew :examples:run         # the demo
./gradlew :freer:jvmBenchmark   # JMH (jvmSmokeBenchmark for a quick pass)
./gradlew :jcstress:run         # JCStress
./gradlew :freer:pitest         # mutation testing, report in freer/build/reports/pitest
./gradlew :freer:dokkaGenerate  # the API reference, in freer/build/dokka/html
```

A public API change needs `./gradlew updateKotlinAbi` and the updated dumps committed. To release, push a tag `v1.2.3` on a commit of the main branch; the workflow runs the checks, publishes to Maven Central and creates the GitHub release.

## License

[MIT](LICENSE)
