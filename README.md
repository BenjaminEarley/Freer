# Freer

[![CI](https://github.com/BenjaminEarley/Freer/actions/workflows/ci.yml/badge.svg)](https://github.com/BenjaminEarley/Freer/actions/workflows/ci.yml)

Extensible Effects for Kotlin Multiplatform, based on [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) by Oleg Kiselyov and Hiromi Ishii.

Programs are pure data structures that describe computations with effects. Effects are handled by composable interpreters that can be stacked, reordered, and swapped — separating *what* a program does from *how* it's executed. A `Program` value is a reusable description: it can be interpreted any number of times, under different handler stacks.

## Installation

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

The low-level `interpret` hands the rule a `resume` continuation. `resume(response)` is the rest of the program with `response` as the effect's result, as a program that nothing has run yet: return it to continue, transform it with `flatMap` or `map` or bind it in a `program { }` block to work on the rest's result, call it more than once to resume the same continuation more than once, or do not call it at all to cut the program short. Every form runs on a constant stack: the rule's own call is run by the handler's loop in place, and any other form goes back to the enclosing lazy evaluation, which runs it one level down and returns before the next effect (`TrampolineScenarios.kt`, scenarios 12 and 17 to 19, each at 100K effects on a 256 KB stack). A continuation resumed more than once is immutable data, so the resumptions are independent; a rule that binds the resumed program in a block is single-shot, as any block is.

Handler responses are not type-checked against the effect's declared response type `R`: nothing ties a rule's return value to `R`, so a wrong response surfaces later as a `ClassCastException` at the value's use site, not at the handler. The one exception is a `Program`-valued response from `handle`, `intercept` or `handleS` — usually a sub-program missing its `.bind()` — which is rejected immediately with an `IllegalStateException` naming the effect and pointing at the missing `.bind()`; use `interpret` when a rule genuinely needs to return a program, and for any `intercept` over such an effect too, since its `proceed()` pass-through would be rejected the same way. Watch for rules whose last expression isn't the intended response — e.g. a collection mutator returning `Boolean` from a rule for a `Unit` effect. A continuation or a response built against the effect value would be checked at compile time wherever the effect's declared type is concrete, but the three built-in declarations over `Any?` (`Reader.Ask`, `State.Get`, `NonDet.Choose`) and a generic effect matched through a star projection cannot be checked by anything, because the type is erased, which is why the API stays untyped.

A `Program` is immutable, handled or not, so one value can be shared between threads and interpreted from all of them at once: every interpretation creates its own coroutines, handler loops and handler state, and nothing of it is visible to another. What is not confined is the code the program carries. Rules, `flatMap` functions, block bodies and `IO` thunks run on the interpreting thread once per interpretation, and whatever they capture is shared by every interpretation, so a rule that mutates a captured collection, or the initial state of a `handleS` in place, needs the synchronization any shared mutable state needs. The KDoc of `Program` states the contract in full. It is checked by `freer/src/commonTest/kotlin/com/benjaminearley/freer/ConcurrentInterpretationTest.kt`, which interprets one shared value (a `program { }` block under per-coroutine handlers, an already-handled program, a middleware, a multi-shot search, a generator continuation and an `io()` run whose thunks hop threads) from 32 coroutines on `Dispatchers.Default` on every target, and on the JVM under [JCStress](https://github.com/openjdk/jcstress) in `jcstress/`, where the same values are interpreted by two actors per iteration and a freshly built program is published through a plain field to a reader on another thread.

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

Middleware has a real per-effect cost: `proceed()` re-emits the effect for a full downstream dispatch round trip, measuring a few times a plain handler — keep `intercept` off hot-path effects. The `interpret` form below is the cheap one, 24 to 36 ns and 232 to 304 B per effect against 64 to 70 ns and 472 to 544 B for `intercept` then `handle` (JDK 21 and 17, see [Performance](#performance)), and it is also the form that works under multi-shot handlers. A stateful `handleS` costs only slightly more than a plain `handle` per effect (the state pair and its boxing).

Middleware on a multi-shot effect (such as `NonDet`, see below) cannot use `intercept`: `proceed()` suspends the rule block, and the downstream handler then resumes that one-shot block once per option. Write it with `interpret` instead, re-emitting the effect and continuing with `flatMap`, which is replayable:

```kotlin
fun <A> Program<A>.passChoices(): Program<A> =
    interpret<NonDet<*>, A> { op, resume -> perform(op).flatMap { resume(it) } }
```

Middleware on an *unrelated* effect, or a rule that answers without calling `proceed()`, is fine under a multi-shot handler.

## Errors & Exceptions

Failures are effects too: `raise(reason)` performs a `Failure.Raise` effect, and `attempt()` materializes the program's outcome as a `Result`. `recover { reason -> ... }` recovers in place: the failed program is abandoned from the failure point and the fallback program replaces it — a failure inside the fallback propagates to the next outer handler (see `Failure.kt` in the effects package and the demo). Effects the fallback performs are only seen by handlers applied *after* the `recover`, so a fallback that logs needs the log handler outside the `recover`. The names `raise`, `attempt` and `recover` follow Arrow's naming for the same operations.

Handler order decides failure semantics: applying `attempt()` before a stateful handler keeps the state written before the failure, applying it after rolls the whole result back — transactional behavior is a call-site choice, not a handler feature (Scenario 7 in the demo).

Exceptions are *not* part of the model: any exception raised while a bound program is evaluated — thrown by a handler, an IO thunk, a bound pure `program { }`, or a `flatMap` lambda — propagates straight out of the interpretation. `try/catch` around `.bind()` inside `program { }` can never catch it, and `finally` blocks in the abandoned program do not run. `.run()` does not catch it either: its `Result` captures only unhandled-effect failures, so a handler exception propagates out of `run()` as well. Use effects (`raise`/`attempt`/`recover`) for recoverable failures.

## Built-in Effects

The library ships general-purpose effects in `com.benjaminearley.freer.effects`, under `freer/src/commonMain/kotlin/com/benjaminearley/freer/effects/` (plus `ioBlocking()` in `freer/src/jvmMain/kotlin/com/benjaminearley/freer/effects/IOBlocking.kt`):

- **State** (`State.kt`) — `getState<S>()`, `putState(value)` and `modifyState { s -> ... }`, handled by `runState(initial)`, or by `runStateWithFinal(initial)`, which also returns the final state as a `Pair`. `transactional()` runs a sub-program as a transaction: its writes hit a local copy of the state, and the commit only runs if the program completes.
- **Reader** (`Reader.kt`) — `ask<E>()` reads an environment supplied by `runReader(env)`; `local { }` is a handler built on `interpret` that answers the `Ask`s inside its sub-program itself with the transformed environment, while `Ask`s outside the sub-program still reach the downstream handler.
- **Failure** (`Failure.kt`) — `raise(reason)`, `attempt()` and `recover { }`, see [Errors & Exceptions](#errors--exceptions).
- **Nondeterminism & committed choice** (`NonDet.kt`) — `choose(options)` with a `collectAll()` handler that resumes the *same* captured continuation once per option and collects every branch's result; multi-shot resumption like this is impossible with one-shot `suspend` coroutines. `guard(condition)` prunes a branch, `orElse(first, second)` is a binary choice. Plus `msplit` (first answer + rest-of-search as a program), `once` (commit to the first answer, prune the rest), and `ifte` (soft cut). `program { }` blocks and the rule blocks of `handle`, `intercept` and `handleS` are one-shot coroutines, so one fails under these handlers only when its *own* continuation is resumed more than once: the block binds the choice itself, proceeds on it, or binds a sub-program that performs it. A `program { }` created inside a `flatMap` closure after the choice is fine (each branch gets a fresh one), as is a rule that answers without suspending or middleware on an unrelated effect — `local` and `transactional` are built this way and work under `collectAll`.
- **Generators** (`Generators.kt`) — `yieldValue(value)` performs an `Emit.Yield` effect, and the `generator()` handler reifies the continuation as data (`Generator.Yielded` holds the yielded value and the rest of the program as a callable value); the driver decides when to resume.
- **IO** (`IO.kt`, `IOBlocking.kt`) — `performIO { }` and the edge runners `io()` and `ioBlocking()`, see [Async I/O](#async-io).

The state and reader types are erased: `State.Get` and `Reader.Ask` are declared over `Any?`, so `getState<S>()` and `ask<E>()` cast to the type their caller names and no handler can check it, and a handler stack carries one State type and one Reader type (a second type needs a second effect class, which is how the paper's two-reader examples are ported). Call sites sometimes need explicit type arguments:

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
var step = countdown.generator<Int, String>().runOrThrow()
while (step is Generator.Yielded) {
    println(step.value)                             // 3, 2, 1
    step = step.resume().runOrThrow()
}
```

## Examples

`examples/` is a JVM app that runs every demo in sequence (`./gradlew :examples:run`, entry point `examples/src/main/kotlin/com/benjaminearley/freer/examples/Main.kt`):

- **Accounting** (`accounting/`) — a money transfer over custom `KVStore`, `FraudCheck` and `Logger` effects, with audit middleware, async I/O and error handling.
- **Bind-chain comparison** (`BindChainComparison.kt`) — Scenario 5, see [Performance](#performance).
- **Error recovery, handler ordering & transactions** (`ErrorHandling.kt`) — `recover`, the state-rollback-vs-state-survives ordering demo, and `transactional()`.
- **Generators** (`GeneratorExample.kt`), **nondeterminism & committed choice** (`NonDetExamples.kt`) and **Reader with `local`** (`ReaderExample.kt`) — small drivers for the built-in effects.

## Semantics

The interpreter in `FFree.kt` is checked against a specification: a naive tree interpreter under `freer/src/commonTest/kotlin/com/benjaminearley/freer/reference/` that transcribes the Haskell accompanying Kiselyov & Ishii, [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) (Haskell 2015), namely [`Eff1.hs`](https://okmij.org/ftp/Haskell/extensible/Eff1.hs). `Val`/`E` become `Ref.Pure`/`Ref.Impure` with a plain closure as the continuation (the type-aligned queue of `FTCQueue1.hs` is the optimization this library's `Pipeline` implements, and the model omits it), and `handle_relay`, `handle_relay_s` and `interpose` are transcribed as `Ref.kt`'s `handleRelay`, `handleRelayS` and `interpose`. `RefEffects.kt` transcribes the paper's handlers for Reader (`runReader`, `local`), State (`runState`, `transactionState`), exceptions (`runError`, `catchError`), nondeterminism (`makeChoice`, `msplit`, `ifte`, `once`) and coroutines (`runC`) over this library's effect types, under this library's names. The model recurses on every bind and every effect, so it is not stack safe; it exists only to say what a `Program` must compute.

The worked examples of `Eff1.hs` are ported as golden tests under the names the Haskell gives them (`t1`, `t3`, `rdwr`, `ex2`, `ter1`–`ter4`, `test_ifte`, `c1`–`c7'`, …), with the expected values the Haskell source states: `PaperExamplesTest.kt` runs them through this library and `reference/RefPaperExamplesTest.kt` through the model. Where the paper relies on two effects of one family being told apart by a type index (two Readers, in `t2`, `t4` and `c7`), the port uses a second effect class, since this library tells effects apart by class. Four examples the Haskell defines without stating a value (`tsplitr10`–`tsplitr21`), one whose value it leaves implicit (`testCA_run`) and two of this project's own (the transactional cases) carry hand-derived values, marked as such in the tests, until a run under GHC confirms them; the coroutine examples (`c1`–`c7'`) are commented out in the current `Eff1.hs`, and the tests keep the values its comments record.

The semantics are also stated as laws and checked as properties, with [kotest-property](https://kotest.io/docs/proptest/property-based-testing.html) generating the programs: random trees over a counter effect, State, Reader, nondeterminism and Failure (`freer/src/commonTest/kotlin/com/benjaminearley/freer/laws/Expr.kt`), compiled into flatMap chains, into `program { }` blocks and into the model, under random complete handler stacks that mix every handler style the library offers, including `local`, `transactional` and middleware (`laws/Stacks.kt`). Every property runs on every tested target, and a failing case is shrunk and reported with its seed.

- **Library equals model** (`laws/DifferentialTest.kt`): the optimized interpreter and the model agree on thousands of generated programs under generated stacks, which covers `interpret`, `interpretS`, `handle`, `handleS`, `intercept` and every built-in handler against the transcription of `Eff1.hs`.
- **Monad laws** (`laws/MonadLawsTest.kt`): left and right identity and associativity, under `flatMap` and under `program { }` with `bind()` ([Wadler, *The essence of functional programming*, 1992](https://dl.acm.org/doi/10.1145/143165.143169)).
- **Blocks equal chains** (`laws/ProgramBlockTest.kt`): `program { p.bind() }` equals `p` whenever every bound sub-program produces its value at most once; the property computes that count in the model and demands the single-shot error exactly when the count reaches two, for the outer block and for a program built entirely from blocks. This is this library's own law, since blocks are one-shot coroutines.
- **`interpretS` equals `interpret`** (`laws/InterpretSTest.kt`): the counter handled with `interpretS` equals the same counter handled with `interpret`, threading the state as a function in the result, in the style of the canonical state handler of [Plotkin & Pretnar, *Handling Algebraic Effects*, 2013](https://lmcs.episciences.org/705), wherever the handler sits in the stack.
- **State** (`laws/StateLawsTest.kt`): put-put, put-get, get-put and get-get, each in context after a random prefix and before a random continuation ([Gibbons & Hinze, *Just do It: Simple Monadic Equational Reasoning*, ICFP 2011](https://www.cs.ox.ac.uk/jeremy.gibbons/publications/mr.pdf), §6, after Plotkin & Power).
- **Reader** (`laws/ReaderLawsTest.kt`): ask-ask, an unused ask changes nothing, `local` applies its transform to `ask`, `local` of a value is the value, `local` distributes over bind, nested `local`s compose, and `local` of the identity changes nothing ([the `MonadAsk` and `MonadReader` laws of purescript-transformers](https://pursuit.purescript.org/packages/purescript-transformers/docs/Control.Monad.Reader.Class) and [the proposed laws for the mtl classes](https://mail.haskell.org/pipermail/libraries/2019-October/030038.html)).
- **Nondeterminism** (`laws/NonDetLawsTest.kt`): `orElse` is associative with the empty choice as identity, the empty choice is a left zero of bind, bind distributes over `orElse` ([Gibbons & Hinze, §4](https://www.cs.ox.ac.uk/jeremy.gibbons/publications/mr.pdf)), `choose` is nested `orElse` and bind distributes over it (`choose lst = msum $ map return lst` in `Eff1.hs`), and the laws of `msplit`, `once` and `ifte` from [Kiselyov, Shan, Friedman & Sabry, *Backtracking, Interleaving, and Terminating Monad Transformers*, ICFP 2005](https://okmij.org/ftp/Computation/LogicT.pdf): splitting the empty choice finds nothing, splitting an answer in front finds it and the rest, `once` commits to the answer in front, and the soft cut runs its then-branch on an answer and its otherwise-branch on none. Two laws hold only with read-only handlers inside the nondeterminism handler and are checked under that condition: split-then-unsplit is invisible, and the soft cut over an answer in front equals the then-branch on it joined with the rest. The split explores the first option before the choice is re-made, so a handler inside that keeps state or cuts the program short sees a different order of events; `tsplitr20` and `tsplitr21` are the paper's own instance.
- **Handler order** (`laws/HandlerOrderingTest.kt`): the claims of [Errors & Exceptions](#errors--exceptions) as properties over random failure-free programs: a failure handler inside the state handler keeps the writes made before the failure, outside it drops the whole result, a transaction drops the writes of a failed program and is invisible for a finished one, and without a failure the order does not matter (`ter1`–`ter4` and `ex2_1`/`ex2_2` are the paper's instances).

How much these tests pin down is measured with [Pitest](https://pitest.org) over the JVM classes (`./gradlew :freer:pitest`, half a minute; the run fails below a score of 66%). Of the 239 mutants that Pitest's default operators make in `FFree.kt`, `ProgramDsl.kt` and the built-in handlers, the tests detect 164, and each of the 75 that survive is one of: Kotlin compiler artifacts (null-check intrinsics on casts, coroutine state machines, the never-executed originals of inline functions), fast paths that are equivalent to the path they skip (among them the identity checks by which the loops recognise a returned continuation), null branches and defensive branches that the interpreter's invariants make unreachable, unused accessors, three guards that only a data race can observe (covered by a JCStress test instead), and one Pitest artefact where the hand-applied mutation does fail its test. None is a behaviour the tests leave open. The full operator set makes 1162 mutants, of which 881 are detected. Mutation testing added thirteen tests, none of them a law: a continuation outliving its run, the two edge runners naming an unhandled effect, the error names for anonymous effects, the balance and maximum of the nesting counter, the stateful interpreter's second resumption carrying its own state, and a block completing with its value or its exception.

## Performance

The implementation uses a [type-aligned queue](https://okmij.org/ftp/Haskell/Reflection.html) for O(n) interpretation of both left- and right-associated bind chains, and lazy continuations plus queue concatenation for stack safety — 100K+ effects through stacked handlers, deep non-tail recursion, long sequential pure-bind chains, rules that transform or bind the resumed program, state threaded as functions in the result, nested `program { }` recursion, and long sequential nondeterministic choices all run on a constant stack (see `freer/src/commonTest/kotlin/com/benjaminearley/freer/TrampolineScenarios.kt`).

The numbers below come from the JMH benchmarks in `freer/src/jvmBenchmark/` ([kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark); `./gradlew :freer:jvmBenchmark` runs them, `:freer:jvmSmokeBenchmark` is a quick pass, and `:freer:jvmBenchmarkJar` builds a jar that `java -jar … -prof gc` runs under JMH's allocation profiler, which is where the bytes come from). One forked JVM per case, five warm-up and five measurement iterations of one second, on an Apple-silicon Mac. Per effect, for a `program { }` block of 1000 effects:

| per effect | JDK 17 | JDK 21 |
|---|---|---|
| `handle` | 10.6 ns, 144 B | 8.6 ns, 72 B |
| `interpret`, the resumed program returned | 8.0 ns, 72 B | 8.4 ns, 72 B |
| `interpret`, the resumed program transformed in place | 19.3 ns, 208 B | 18.0 ns, 208 B |
| `handleS` | 15.0 ns, 160 B | 9.3 ns, 88 B |
| `interpretS` | 8.8 ns, 86 B | 8.3 ns, 86 B |
| `handle` behind one relaying handler | 15.5 ns, 200 B | 26.2 ns, 200 B |
| `intercept` then `handle` | 69.9 ns, 544 B | 64.1 ns, 472 B |
| middleware via `interpret`, then `handle` | 35.7 ns, 304 B | 23.5 ns, 232 B |
| a flatMap chain, `interpret` with the resumed program returned | 4.3 ns, 64 B | 4.5 ns, 64 B |
| a raw suspend loop, parked and resumed | 4.9 ns, 0 B | 4.3 ns, 0 B |

The gap between `handle` and `interpret` is the rule's coroutine: a scope object and a copy of the rule lambda per effect. On JDK 21 C2's escape analysis eliminates both, so a `handle` rule that returns without suspending costs nothing over a direct resume; on JDK 17 they are allocated, 72 bytes per effect, with identical inlining decisions on both (checked with `-XX:+PrintInlining`). The gap to the raw suspend loop is the reified continuation itself, a handful of small objects per effect, which is what makes a program replayable, interceptable and multi-shot. `intercept` is the expensive style: `proceed()` suspends the rule block and performs the effect again downstream, so each intercepted effect is handled twice and parks a coroutine in between; middleware written with `interpret`, which performs the effect again and resumes with the answer from a `flatMap`, parks nothing and costs a third to a half of it. Transforming the resumed program in place costs the queued step and the node that carries it, 136 bytes over returning it, and one more pass through the enclosing loop per effect. A relayed effect on JDK 21 is the one case the lazy `resume` made dearer: the continuation object can now escape as the rule's result, so C2 no longer scalar-replaces it there, 40 bytes and 5 ns per effect, measured before and after in one session. JDK 17's `handle` moves between 10 and 15 ns from run to run with the same bytes; the allocation column is the stable one.

Bind chains stay linear in both associations (`BindChainBenchmark`, construction timed with interpretation): per element, the left-associated chain costs 18 ns and 264 B at 10K elements and 24 ns and 304 B at 100K, the right-associated one 4 ns and 64 B at 10K and 5 ns and 68 B at 100K (JDK 17). The race of the demo's Scenario 5 (`examples/src/main/kotlin/com/benjaminearley/freer/examples/BindChainComparison.kt`, `IterateeRaceBenchmark` in JMH) feeds n ints through n left-associated binds in three encodings: at n = 4000 the closure-composed iteratee takes 55 ms and allocates 440 MB, quadratic (3.8x the time of n = 2000), raw `suspend` lambdas 72 µs, and `Program` 78 µs. At n = 8000 the suspend encoding overflows the 1 MB stack of a JMH worker thread, because its continuation is a chain of nested frames; `Program`'s queue is flat (the demo runs it on the main thread).

**Against evidence passing and runtime continuations.** This library reifies every effect: a `perform` is a suspension with a queued continuation, and a handler is a loop over suspensions, which is the free-monad design with a type-aligned queue. [Evidence passing](https://dl.acm.org/doi/10.1145/3473576) (Xie & Leijen, *Generalized Evidence Passing for Effect Handlers*, ICFP 2021) compiles handlers to a vector of evidence passed down the call stack, so an operation whose handler resumes once, in tail position, becomes a function call with no continuation captured, and only the other handlers capture a continuation and bubble it up; runtime delimited continuations, as in [OCaml 5](https://dl.acm.org/doi/10.1145/3453483.3454039) or the JVM's own one-shot continuations under virtual threads, copy the stack segment up to the handler at each operation and cannot resume it twice. The table above is the price of reification: 8 to 11 ns and 72 to 144 B for a `handle` against 4 to 5 ns and 0 B for a raw suspend loop, the cost of allocating and queueing one continuation per effect, which evidence passing would make a virtual call in the tail-resumptive case and runtime continuations a stack copy. What reification buys is what the rest of this README relies on: a program is a value, interpreted under different handler stacks, replayed, intercepted and resumed more than once, with one cost model on every target, including Wasm, where no runtime continuation exists.

The remaining stack bound is the depth of nested handler applications: each applied handler is an interpreter loop that stays on the stack while its inner program runs. Measured from inside the innermost rule with `StackWalker`, with 4 to 32 handlers nested outside it, on JDK 17 (`freer/src/jvmTest/kotlin/com/benjaminearley/freer/StackDepthTest.kt`), every handler style costs 3 JVM frames per level on first entry and 2 when the levels are re-entered after a relayed effect, and a 256 KB thread stack runs at least 256 nested handlers (`SmallStackTest.kt`, which also runs every stack-safety scenario at 100K effects on that stack, 32 times smaller than the default one). That the bound depends on handler nesting and not on program length is checked directly: a counter in the interpreter tracks how deeply `force()` and the interpreter loops nest, and `freer/src/commonTest/kotlin/com/benjaminearley/freer/NestingDepthTest.kt` runs every scenario at two lengths on every target and requires the same depth, between 1 and 5, at both. The counter is off outside tests and costs nothing measurable with it on in the benchmarks above.

## Project layout

- `freer/` — the library: common code in `freer/src/commonMain/`, JVM-only code (including `ioBlocking()`) in `freer/src/jvmMain/`, and the implementation shared by Wasm and native in `freer/src/nonJvmMain/`. Tests in `freer/src/commonTest/`, including the specification interpreter and the golden tests under `reference/` and the property-based law tests under `laws/` (run on the JVM, Wasm on Node, `linuxX64`, `macosArm64`, `iosSimulatorArm64` and `mingwX64`; `iosArm64` and `linuxArm64` are compiled but not tested, as CI has no hardware to run them) and `freer/src/jvmTest/` (`IoBlockingTest.kt` and the stack measurements `SmallStackTest.kt` and `StackDepthTest.kt`). The JMH benchmarks are a separate compilation in `freer/src/jvmBenchmark/`. Public API dumps in `freer/api/`.
- `examples/` — the JVM demo app.
- `jcstress/` — the JCStress concurrency tests: shared `Program` values interpreted from two threads at once under the OpenJDK memory-model stress harness (`./gradlew :jcstress:run`), with the shared programs in Kotlin and the test classes in Java, as the harness generates their runners with a javac annotation processor.
- `.github/workflows/` — `ci.yml` (a Linux job for the JVM, Wasm and `linuxX64` tests, the API check and the demo; a macOS job for the macOS and iOS simulator tests that also assembles every Apple target; a Windows job for the `mingwX64` tests; a JCStress job for the concurrency tests in quick mode) and `release.yml` (tag-driven publishing to Maven Central).

## Contributing / Releasing

```sh
./gradlew check                 # tests on JVM, Node Wasm and the host's native targets, plus the API check
./gradlew :examples:run         # the demo
./gradlew :freer:jvmBenchmark   # the JMH benchmarks (jvmSmokeBenchmark for a quick pass)
./gradlew :jcstress:run         # the JCStress concurrency tests (--args="-m default" for a longer run)
./gradlew :freer:pitest         # mutation testing of the JVM classes (report in freer/build/reports/pitest)
./gradlew publishToMavenLocal   # install the current version into ~/.m2
```

On Linux, `check` runs the native tests for `linuxX64`; on an Apple-silicon Mac, for `macosArm64` and also `iosSimulatorArm64`. Building the Apple targets needs Xcode installed. The `mingwX64` tests only run on Windows, in the CI's Windows job.

The library builds in explicit API mode, so every public declaration needs an explicit visibility modifier and return type. The Kotlin Gradle plugin's built-in ABI validation checks the public API against the dumps in `freer/api/` as part of `check` (task `checkKotlinAbi`); when you change the public API intentionally, run `./gradlew updateKotlinAbi` and commit the updated dumps with the change.

`main` stays on a `-SNAPSHOT` version (in `gradle.properties`). To release, push a tag `vX.Y.Z` on a commit that is already on `main`: the release workflow refuses any other commit, takes the version from the tag, runs `check`, signs and publishes to Maven Central, then creates the GitHub release for the tag with generated notes. It needs four repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_PASSWORD`.

## License

[MIT](LICENSE)
