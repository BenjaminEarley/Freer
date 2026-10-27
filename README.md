# Free-er

Extensible Effects for Kotlin, based on [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) by Oleg Kiselyov and Hiromi Ishii.

Programs are pure data structures that describe computations with effects. Effects are handled by composable interpreters that can be stacked, reordered, and swapped — separating *what* a program does from *how* it's executed. A `Program` value is a reusable description: it can be interpreted any number of times, under different handler stacks.

Running main.kt:
```sh
kotlinc -include-runtime -d /tmp/ffree.jar $(find src -name '*.kt') && java -jar /tmp/ffree.jar
```

Running the tests and the benchmark:
```sh
kotlinc -include-runtime -d /tmp/ffree-tests.jar $(find src test -name '*.kt')
java -cp /tmp/ffree-tests.jar TrampolineTestKt
java -cp /tmp/ffree-tests.jar SemanticsTestKt
java -cp /tmp/ffree-tests.jar BenchmarkKt
```

## Quick Example

### 1. Define an effect

```kotlin
import ffree.*

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

Handlers dispatch on the erased effect class: `handle<Get<String>, A>` also matches a `Get<Int>`, and the mismatch surfaces as a `ClassCastException` at the use site. Always dispatch on the star-projected family (`Get<*>`, `KVStore<*>`).

The low-level `interpret` hands the rule a `resume` continuation with a contract: called directly (inside the rule), it must be called at most once and its result returned unchanged — the interpreter trampolines it for stack safety and throws on misuse. To transform or combine results, call `resume` from a genuinely deferred context (e.g. bind it inside a `program { }` block — `flatMap` on an already-`Done` program still runs eagerly), or just use `handle`/`intercept`. Calling `resume` from a deferred context is stack-safe: a deferred program is never forced from inside a step or a bind, so a rule that binds `resume` on every effect still runs on a constant stack.

Handler responses are not type-checked against the effect's declared response type `R`: nothing ties a rule's return value to `R`, so a wrong response surfaces later as a `ClassCastException` at the value's use site, not at the handler. The one exception is a `Program`-valued response from `handle`, `intercept` or `handleS` — usually a sub-program missing its `.bind()` — which is rejected immediately with an `IllegalStateException` naming the effect and pointing at the missing `.bind()`; use `interpret` when a rule genuinely needs to return a program. Watch for rules whose last expression isn't the intended response — e.g. a collection mutator returning `Boolean` from a rule for a `Unit` effect.

## Async I/O

Handlers that need real I/O emit `IO` effects via `performIO { }`. A single `suspend` interpreter at the edge handles them all:

```kotlin
import ffree.effects.io
import ffree.effects.performIO

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

`io()` is a terminal `suspend` function — call it from a coroutine, and apply it after every other handler so only `IO` effects remain. Threading: thunk resumptions re-enter through the calling context's `ContinuationInterceptor`, so under a coroutine dispatcher interpretation stays on that dispatcher. In interceptor-free contexts (a plain or bare `suspend` `main`) the first thunk that resumes on a foreign thread would migrate the rest of interpretation onto it — use the blocking edge runner there instead:

```kotlin
val name = greeter
    .runConsoleAsync()
    .ioBlocking()     // terminal: blocks the caller, interpretation never leaves this thread
```

`ioBlocking()` runs every interpretation step on the calling thread; async thunks may hop threads internally, but their results are handed back before interpretation continues, so handler state needs no synchronization.

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

Middleware has a real per-effect cost: `proceed()` re-emits the effect for a full downstream dispatch round trip, measuring several times a plain handler — keep `intercept` off hot-path effects. A stateful `handleS` costs roughly twice a plain `handle` per effect, for the state pair and boxing.

Middleware on a multi-shot effect (such as `NonDet`, see below) cannot use `intercept`: `proceed()` suspends the rule block, and the downstream handler then resumes that one-shot block once per option. Write it with `interpret` instead, re-emitting the effect and continuing with `flatMap`, which is replayable:

```kotlin
fun <A> Program<A>.passChoices(): Program<A> =
    interpret<NonDet<*>, A> { op, resume -> perform(op).flatMap { resume(it) } }
```

Middleware on an *unrelated* effect, or a rule that answers without calling `proceed()`, is fine under a multi-shot handler.

## Errors & Exceptions

Failures are effects too: `fail(reason)` performs a `Raise` effect, and `raise()` materializes the program's outcome as a `Result`. `catchError { reason -> ... }` recovers in place: the failed program is abandoned from the failure point and the recovery program replaces it — a failure inside the recovery propagates to the next outer handler (see `effects/Error.kt` and the demo). Effects the recovery performs are only seen by handlers applied *after* the `catchError`, so a recovery that logs needs the log handler outside the catch.

Handler order decides failure semantics: applying `raise()` before a stateful handler keeps the state written before the failure, applying it after rolls the whole result back — transactional behavior is a call-site choice, not a handler feature (Scenario 7 in the demo).

JVM exceptions are *not* part of the model: any exception raised while a bound program is evaluated — thrown by a handler, an IO thunk, a bound pure `program { }`, or a `flatMap` lambda — propagates straight out of the interpretation. `try/catch` around `.bind()` inside `program { }` can never catch it, and `finally` blocks in the abandoned program do not run. `.run()` does not catch it either: its `Result` captures only unhandled-effect failures, so a handler exception propagates out of `run()` as well. Use effects (`Raise`/`Result`) for recoverable failures.

## More Examples

`src/examples/` goes beyond the accounting demo:

- **Generators** (`Generators.kt`) — a `Yield` effect whose handler reifies the continuation as data (`Status.Yielded` holds the rest of the program as a callable value); the driver decides when to resume.
- **Nondeterminism & committed choice** (`NonDet.kt`) — `choose(options)` with a handler that resumes the *same* captured continuation once per option and collects every branch's result; multi-shot resumption like this is impossible with one-shot `suspend` coroutines. Plus `msplit` (first answer + rest-of-search as a program), `once` (commit to the first answer, prune the rest), and `ifte` (soft cut). `program { }` blocks and the rule blocks of `handle`, `intercept` and `handleS` are one-shot coroutines, so one fails under these handlers only when its *own* continuation is resumed more than once: the block binds the choice itself, proceeds on it, or binds a sub-program that performs it. A `program { }` created inside a `flatMap` closure after the choice is fine (each branch gets a fresh one), as is a rule that answers without suspending or middleware on an unrelated effect — `local` and `transactional` are built this way and work under `collectAll`.
- **Error recovery, handler ordering & transactions** (`ErrorHandling.kt`) — `catchError`, the state-rollback-vs-state-survives ordering demo, and `transactional()` (writes hit a local copy of the state; the commit only runs if the program completes).
- **Reader with `local`** (`Reader.kt`) — an environment effect where `local { }` overrides the value for one sub-program via `intercept`, without consuming the effect.

## Performance

The implementation uses a [type-aligned queue](https://okmij.org/ftp/Haskell/Reflection.html) for O(n) interpretation of both left- and right-associated bind chains (see `test/Benchmark.kt`), and a trampoline plus queue concatenation for stack safety — 100K+ effects through stacked handlers, deep non-tail recursion, and long sequential pure-bind chains all run on a constant stack (see `test/TrampolineTest.kt`). The demo's Scenario 5 (`src/examples/BindChainComparison.kt`) races three encodings of one left-associated bind chain: a closure-composed iteratee (O(n²)), raw `suspend` lambdas (O(n), but one-shot and opaque), and `Program` (O(n), and a replayable, interceptable value). The remaining stack bound is the depth of nested handler applications: each applied handler is an interpreter loop that stays on the stack while its inner program runs, so stack use grows with how many handlers are nested inside one another — not with program nesting depth or effect count.

## License

[MIT](LICENSE)
