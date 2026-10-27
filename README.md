# Freer

[![CI](https://github.com/BenjaminEarley/Freer/actions/workflows/ci.yml/badge.svg)](https://github.com/BenjaminEarley/Freer/actions/workflows/ci.yml)

Extensible Effects for Kotlin Multiplatform, based on [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) by Oleg Kiselyov and Hiromi Ishii.

Programs are pure data structures that describe computations with effects. Effects are handled by composable interpreters that can be stacked, reordered, and swapped — separating *what* a program does from *how* it's executed. A `Program` value is a reusable description: it can be interpreted any number of times, under different handler stacks.

## Installation

The first release, `0.1.0`, is not on Maven Central yet; the coordinates below take effect once it is published.

In a Kotlin Multiplatform project, add the library to `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.benjaminearley:freer:0.1.0")
        }
    }
}
```

In a plain JVM project, Gradle resolves the JVM variant from the same coordinates:

```kotlin
dependencies {
    implementation("com.benjaminearley:freer:0.1.0")
}
```

Maven and other non-Gradle JVM builds do not read Gradle module metadata, so they depend on the JVM artifact directly: `com.benjaminearley:freer-jvm:0.1.0`.

Targets: JVM (bytecode level 11), Wasm (`wasmJs`), `macosArm64`, `iosArm64`, `iosSimulatorArm64`, `linuxX64`, `linuxArm64` and `mingwX64`. The core API lives in `com.benjaminearley.freer`, the built-in effects in `com.benjaminearley.freer.effects`. Everything is common code except `ioBlocking()`, which is JVM-only.

## When to use it

Take a refund flow: load the order, call the payment provider, write a ledger entry, notify the customer. As a `Program` it is one value, and who is asking decides how it runs:

- **Production** applies the real handlers — database, provider client, ledger, mail.
- **Tests** apply an in-memory store and a scripted provider that fails on the second call, then assert on the exact sequence of effects performed: no mocking framework, no interfaces to maintain.
- **Dry run** swaps in a handler that returns a planned provider reference without calling anyone and collects the ledger write instead of executing it — the same program, zero side effects, and the entry it *would* have written comes back as data.
- **Audit** is an `intercept` applied in production only: log every provider call with the operator's identity, then `proceed()`. The refund code never learns it exists.
- **Rollback policy** is chosen at the call site, not baked in: handle errors outside the ledger handler and a failed notification rolls the write back; handle them inside and the write survives (see [Errors & Exceptions](#errors--exceptions)).

A `suspend` function with injected dependencies gives you the first two. The rest need flags and wrappers threaded through every call, or cannot be expressed at all, because a `suspend` function's effects happen as it runs and cannot be reordered or withheld afterwards. Skip `Program` for tight loops over millions of trivial effects or for code that only needs sequencing — there the interpreter is pure overhead. The sweet spot is business flows with a handful of effects per run that must be tested, previewed, audited, or replayed.

## Quick Example

### 1. Define an effect

```kotlin
import com.benjaminearley.freer.*

sealed interface Console<out R> : Effect<R>
data class Print(val msg: String) : Console<Unit>
data class ReadLine(val prompt: String) : Console<String>

fun print(msg: String) = perform(Print(msg))
fun readLine(prompt: String) = perform(ReadLine(prompt))
```

### 2. Write a program using the DSL

```kotlin
val greeter: Program<String> = program {
    print("What is your name?").bind()
    val name = readLine("> ").bind()
    print("Hello, $name!").bind()
    name
}
```

The `program { }` block uses `.bind()` to sequence effects. Api inspired by Arrow's Raise DSL.

### 3. Handle the effects

```kotlin
fun <A> Program<A>.console(): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> println(op.msg)
            is ReadLine -> {
                kotlin.io.print(op.prompt)
                readln()
            }
        }
    }
```

The `handle` DSL auto-resumes with the block's return value. For effects that return `Unit` (like `Print`), just do the work. For effects that return a value (like `ReadLine`), return it. Note the qualified `kotlin.io.print`: the effect constructor `print` from step 1 shadows it, and calling *that* here would make the rule return a `Program` instead of printing. That mistake fails fast: the handler throws an `IllegalStateException` naming the effect (`Print`) and pointing at the missing `.bind()`.

### 4. Run it

```kotlin
val name: String = greeter
    .console()
    .runOrThrow()  // extracts the value, or fails naming the unhandled effect
```

Because programs are pure descriptions, `greeter` can be run again — with the same handlers or different ones — and each interpretation replays it from the start. Handlers are lazy too: applying one builds a new description, and interpretation (with its side effects) runs when `.runOrThrow()` forces it.

## Core Concepts

| Concept | Description |
|---------|-------------|
| `Program<A>` | A pure description of a computation that produces `A` |
| `Effect<R>` | An interface for effect types that expect a response of type `R` |
| `perform(effect)` | Suspend the program, requesting an effect to be handled |
| `program { }` | DSL builder — use `.bind()` instead of `flatMap` chains |
| `handle` | Interpret an effect — return the response, resume is automatic |
| `intercept` | Middleware — observe an effect, call `proceed()` to re-emit it |
| `handleS` | Stateful handler — return `newState to response` |
| `interpret` | Low-level handler with explicit `resume` control |
| `.runOrThrow()` | Extract the final value, or fail naming the unhandled effect |
| `.run()` | Extract the outcome as a `Result` — success, or failure naming the unhandled effect |

Handlers dispatch on the erased effect class: for a generic effect `Fetch<T>`, `handle<Fetch<String>, A>` also matches a `Fetch<Int>`, and the mismatch surfaces as a `ClassCastException` at the use site. Always dispatch on the star-projected family (`Fetch<*>`, `Console<*>`).

The low-level `interpret` hands the rule a `resume` continuation with a contract: called directly (inside the rule), it must be called at most once and its result returned unchanged — the interpreter trampolines it for stack safety and throws on misuse. To transform or combine results, call `resume` from a genuinely deferred context (e.g. bind it inside a `program { }` block — `flatMap` on an already-`Done` program still runs eagerly), or just use `handle`/`intercept`. Calling `resume` from a deferred context is stack-safe: a deferred program is never forced from inside a step or a bind, so a rule that binds `resume` on every effect still runs on a constant stack. A direct `resume()` call returns to the rule before the rest of the program has been forced, so code after it in the rule runs before the continuation's next lazy block; effect order is unaffected.

Handler responses are not type-checked against the effect's declared response type `R`: nothing ties a rule's return value to `R`, so a wrong response surfaces later as a `ClassCastException` at the value's use site, not at the handler. The one exception is a `Program`-valued response from `handle`, `intercept` or `handleS` — usually a sub-program missing its `.bind()` — which is rejected immediately with an `IllegalStateException` naming the effect and pointing at the missing `.bind()`; use `interpret` when a rule genuinely needs to return a program, and for any `intercept` over such an effect too, since its `proceed()` pass-through would be rejected the same way. Watch for rules whose last expression isn't the intended response — e.g. a collection mutator returning `Boolean` from a rule for a `Unit` effect.

## Async I/O

Handlers that need real I/O emit `IO` effects via `performIO { }`. A single `suspend` interpreter at the edge handles them all:

```kotlin
import com.benjaminearley.freer.effects.io
import com.benjaminearley.freer.effects.performIO

fun <A> Program<A>.runConsoleAsync(): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> performIO { sendToRemoteLog(op.msg) }.bind()
            is ReadLine -> performIO { fetchInputFromApi() }.bind()
        }
    }

val name = greeter
    .runConsoleAsync()
    .io()             // suspend terminal: runs all IO effects, returns the value
```

`io()` is a terminal `suspend` function available on every target — call it from a coroutine, and apply it after every other handler so only `IO` effects remain. Threading: thunk resumptions re-enter through the calling context's `ContinuationInterceptor`, so under a coroutine dispatcher interpretation stays on that dispatcher. In interceptor-free contexts (a plain or bare `suspend` `main`) the first thunk that resumes on a foreign thread would migrate the rest of interpretation onto it — on the JVM, use the blocking edge runner there instead:

```kotlin
val name = greeter
    .runConsoleAsync()
    .ioBlocking()     // JVM only. Terminal: blocks the caller, interpretation never leaves this thread
```

`ioBlocking()` is JVM-only. It runs every interpretation step on the calling thread; async thunks may hop threads internally, but their results are handed back before interpretation continues, so handler state needs no synchronization.

## Middleware

`intercept` observes effects without consuming them. Call `proceed()` to re-emit the effect to a downstream handler:

```kotlin
fun <A> Program<A>.auditConsole(): Program<A> =
    intercept<Console<*>, A> { op, proceed ->
        when (op) {
            is Print -> { println("AUDIT: Print '${op.msg}'"); proceed() }
            is ReadLine -> { println("AUDIT: ReadLine"); proceed() }
        }
    }

val name = greeter
    .auditConsole()   // logs every console op, then re-emits
    .console()        // actually executes them
    .runOrThrow()
```

`proceed()` returns the downstream handler's response, so you can inspect or modify it:

```kotlin
fun <A> Program<A>.uppercaseConsole(): Program<A> =
    intercept<Console<*>, A> { op, proceed ->
        when (op) {
            is ReadLine -> {
                val input = proceed() as String   // get the downstream result
                input.uppercase()                  // modify it
            }
            else -> proceed()
        }
    }
```

A middleware can also perform *new* effects — the demo's `auditKVStore` emits `Log` effects. Handler order matters then: the handler for the emitted effects must sit downstream of the intercept. `prog.auditKVStore().kvStore(db).logger()` works; applying `logger()` before `auditKVStore()` leaves the audit logs unhandled.

Middleware has a real per-effect cost: `proceed()` re-emits the effect for a full downstream dispatch round trip, measuring a few times a plain handler — keep `intercept` off hot-path effects. A stateful `handleS` costs only slightly more than a plain `handle` per effect (the state pair and its boxing).

Middleware on a multi-shot effect (such as `NonDet`, see below) cannot use `intercept`: `proceed()` suspends the rule block, and the downstream handler then resumes that one-shot block once per option. Write it with `interpret` instead, re-emitting the effect and continuing with `flatMap`, which is replayable:

```kotlin
fun <A> Program<A>.passChoices(): Program<A> =
    interpret<NonDet<*>, A> { op, resume -> perform(op).flatMap { resume(it) } }
```

Middleware on an *unrelated* effect, or a rule that answers without calling `proceed()`, is fine under a multi-shot handler.

## Errors & Exceptions

Failures are effects too: `fail(reason)` performs an `Error.Raise` effect, and `raise()` materializes the program's outcome as a `Result`. `catchError { reason -> ... }` recovers in place: the failed program is abandoned from the failure point and the recovery program replaces it — a failure inside the recovery propagates to the next outer handler (see `Error.kt` in the effects package and the demo). Effects the recovery performs are only seen by handlers applied *after* the `catchError`, so a recovery that logs needs the log handler outside the catch.

Handler order decides failure semantics: applying `raise()` before a stateful handler keeps the state written before the failure, applying it after rolls the whole result back — transactional behavior is a call-site choice, not a handler feature (Scenario 7 in the demo).

Exceptions are *not* part of the model: any exception raised while a bound program is evaluated — thrown by a handler, an IO thunk, a bound pure `program { }`, or a `flatMap` lambda — propagates straight out of the interpretation. `try/catch` around `.bind()` inside `program { }` can never catch it, and `finally` blocks in the abandoned program do not run. `.run()` does not catch it either: its `Result` captures only unhandled-effect failures, so a handler exception propagates out of `run()` as well. Use effects (`Error.Raise`/`Result`) for recoverable failures.

## Built-in Effects

The library ships general-purpose effects in `com.benjaminearley.freer.effects`, under `freer/src/commonMain/kotlin/com/benjaminearley/freer/effects/` (plus `ioBlocking()` in `freer/src/jvmMain/kotlin/com/benjaminearley/freer/effects/IOBlocking.kt`):

- **State** (`State.kt`) — `getState<S>()`, `putState(value)` and `modifyState { s -> ... }`, handled by `runState(initial)`, or by `runStateWithFinal(initial)`, which also returns the final state as a `Pair`. `transactional()` runs a sub-program as a transaction: its writes hit a local copy of the state, and the commit only runs if the program completes.
- **Reader** (`Reader.kt`) — `ask<E>()` reads an environment supplied by `runReader(env)`; `local { }` is a handler built on `interpret` that answers the `Ask`s inside its sub-program itself with the transformed environment, while `Ask`s outside the sub-program still reach the downstream handler.
- **Error** (`Error.kt`) — `fail(reason)`, `raise()` and `catchError { }`, see [Errors & Exceptions](#errors--exceptions).
- **Nondeterminism & committed choice** (`NonDet.kt`) — `choose(options)` with a `collectAll()` handler that resumes the *same* captured continuation once per option and collects every branch's result; multi-shot resumption like this is impossible with one-shot `suspend` coroutines. `guard(condition)` prunes a branch, `orElse(first, second)` is a binary choice. Plus `msplit` (first answer + rest-of-search as a program), `once` (commit to the first answer, prune the rest), and `ifte` (soft cut). `program { }` blocks and the rule blocks of `handle`, `intercept` and `handleS` are one-shot coroutines, so one fails under these handlers only when its *own* continuation is resumed more than once: the block binds the choice itself, proceeds on it, or binds a sub-program that performs it. A `program { }` created inside a `flatMap` closure after the choice is fine (each branch gets a fresh one), as is a rule that answers without suspending or middleware on an unrelated effect — `local` and `transactional` are built this way and work under `collectAll`.
- **Generators** (`Generators.kt`) — `yieldValue(value)` performs an `Emit.Yield` effect, and the `coroutine()` handler reifies the continuation as data (`Status.Yielded` holds the yielded value and the rest of the program as a callable value); the driver decides when to resume.
- **IO** (`IO.kt`, `IOBlocking.kt`) — `performIO { }` and the edge runners `io()` and `ioBlocking()`, see [Async I/O](#async-io).

The state and reader types are erased: `getState<S>()` and `ask<E>()` trust the caller, and a handler stack carries one State type and one Reader type. Call sites sometimes need explicit type arguments:

```kotlin
import com.benjaminearley.freer.effects.*

val counter: Program<Int> = program {
    modifyState { n: Int -> n + 1 }.bind()
    getState<Int>().bind()
}
counter.runStateWithFinal(41).runOrThrow()          // (42, 42)

val greet: Program<String> = program { "Hello, ${ask<String>().bind()}!" }
greet.local { e: String -> e.uppercase() }
    .runReader("world")
    .runOrThrow()                                   // "Hello, WORLD!"

val countdown: Program<String> = program {
    for (i in 3 downTo 1) yieldValue(i).bind()
    "liftoff"
}
var status = countdown.coroutine<Int, String>().runOrThrow()
while (status is Status.Yielded) {
    println(status.value)                           // 3, 2, 1
    status = status.resume().runOrThrow()
}
```

## Examples

`examples/` is a JVM app that runs every demo in sequence (`./gradlew :examples:run`, entry point `examples/src/main/kotlin/com/benjaminearley/freer/examples/Main.kt`):

- **Accounting** (`accounting/`) — a money transfer over custom `KVStore`, `FraudCheck` and `Logger` effects, with audit middleware, async I/O and error handling.
- **Bind-chain comparison** (`BindChainComparison.kt`) — Scenario 5, see [Performance](#performance).
- **Error recovery, handler ordering & transactions** (`ErrorHandling.kt`) — `catchError`, the state-rollback-vs-state-survives ordering demo, and `transactional()`.
- **Generators** (`GeneratorExample.kt`), **nondeterminism & committed choice** (`NonDetExamples.kt`) and **Reader with `local`** (`ReaderExample.kt`) — small drivers for the built-in effects.

## Performance

The implementation uses a [type-aligned queue](https://okmij.org/ftp/Haskell/Reflection.html) for O(n) interpretation of both left- and right-associated bind chains (see `freer/src/jvmTest/kotlin/com/benjaminearley/freer/Benchmark.kt`, run with `./gradlew :freer:runBenchmark`), and a trampoline plus queue concatenation for stack safety — 100K+ effects through stacked handlers, deep non-tail recursion, long sequential pure-bind chains, rules that resume from a deferred context, nested `program { }` recursion, and long sequential nondeterministic choices all run on a constant stack (see `freer/src/commonTest/kotlin/com/benjaminearley/freer/TrampolineTest.kt`). The demo's Scenario 5 (`examples/src/main/kotlin/com/benjaminearley/freer/examples/BindChainComparison.kt`) races three encodings of one left-associated bind chain: a closure-composed iteratee (O(n²)), raw `suspend` lambdas (O(n), but one-shot and opaque), and `Program` (O(n), and a replayable, interceptable value). In absolute terms, measured on JDK 26 on an Apple-silicon Mac at 1M effects: about 10 ns and 130 bytes per effect through the `program { }` DSL and `handle`, roughly 2x a raw `suspend` loop doing the same work and 1.4x on the left-associated chain of Scenario 5. That gap is the reified continuation itself — a handful of small objects per effect — which is what makes a program replayable, interceptable, and multi-shot. The remaining stack bound is the depth of nested handler applications: each applied handler is an interpreter loop that stays on the stack while its inner program runs, so stack use grows with how many handlers are nested inside one another — not with program nesting depth or effect count.

## Project layout

- `freer/` — the library: common code in `freer/src/commonMain/`, JVM-only code (including `ioBlocking()`) in `freer/src/jvmMain/`, and the implementation shared by Wasm and native in `freer/src/nonJvmMain/`. Tests in `freer/src/commonTest/` (run on the JVM, Wasm on Node, `linuxX64`, `macosArm64`, `iosSimulatorArm64` and `mingwX64`; `iosArm64` and `linuxArm64` are compiled but not tested, as CI has no hardware to run them) and `freer/src/jvmTest/` (`IoBlockingTest.kt` and the benchmark harness). Public API dumps in `freer/api/`.
- `examples/` — the JVM demo app.
- `.github/workflows/` — `ci.yml` (a Linux job for the JVM, Wasm and `linuxX64` tests, the API check and the demo; a macOS job for the macOS and iOS simulator tests that also assembles every Apple target; a Windows job for the `mingwX64` tests) and `release.yml` (tag-driven publishing to Maven Central).

## Contributing / Releasing

```sh
./gradlew check                 # tests on JVM, Node Wasm and the host's native targets, plus the API check
./gradlew :examples:run         # the demo
./gradlew :freer:runBenchmark   # the JVM benchmark harness
./gradlew publishToMavenLocal   # install the current version into ~/.m2
```

On Linux, `check` runs the native tests for `linuxX64`; on an Apple-silicon Mac, for `macosArm64` and also `iosSimulatorArm64`. Building the Apple targets needs Xcode installed. The `mingwX64` tests only run on Windows, in the CI's Windows job.

The library builds in explicit API mode, so every public declaration needs an explicit visibility modifier and return type. The binary-compatibility validator checks the public API against the dumps in `freer/api/` as part of `check`; when you change the public API intentionally, run `./gradlew apiDump` and commit the updated dumps with the change.

`main` stays on a `-SNAPSHOT` version (in `gradle.properties`). To release, push a tag `vX.Y.Z` on a commit that is already on `main`: the release workflow refuses any other commit, takes the version from the tag, runs `check`, then signs and publishes to Maven Central. It needs four repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_PASSWORD`.

## License

[MIT](LICENSE)
