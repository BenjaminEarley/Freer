/*
 * Freer Monads & Extensible Effects.
 * A Kotlin implementation based on "Freer Monads, More Extensible Effects" by Kiselyov & Ishii.
 * https://okmij.org/ftp/Haskell/extensible/more.pdf
 */

@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer

import kotlin.reflect.KClass

/** The "Existential" type. We use Any? because of type erasure. */
internal typealias Erased = Any?

/**
 * Handle and remove every effect of family [E], mapping the final value with
 * [transformDone]. [rule] receives the effect and a `resume` continuation:
 * `resume(response)` is the rest of the program with `response` as the effect's
 * result, as a program that nothing has run yet. Return it to continue, transform it
 * with [flatMap] or [map] or bind it in a `program { }` block to work on the rest's
 * result, call it more than once to resume the same continuation more than once, or
 * leave it uncalled to cut the program short. Every form runs on a constant stack.
 * Applied lazily, when the program is run.
 */
public inline fun <reified E : Effect<*>, A, B> Program<A>.interpret(
    noinline transformDone: (A) -> Program<B>,
    noinline rule: (E, (Any?) -> Program<B>) -> Program<B>,
): Program<B> = interpretWith(E::class, transformDone, rule)

/**
 * Handle and remove every effect of family [E]. [rule] receives the effect and a
 * `resume` continuation: `resume(response)` is the rest of the program with `response`
 * as the effect's result, as a program that nothing has run yet. Return it to continue,
 * transform it with [flatMap] or [map] or bind it in a `program { }` block to work on
 * the rest's result, call it more than once to resume the same continuation more than
 * once, or leave it uncalled to cut the program short. Every form runs on a constant
 * stack. Applied lazily, when the program is run.
 */
public inline fun <reified E : Effect<*>, A> Program<A>.interpret(noinline rule: (E, (Any?) -> Program<A>) -> Program<A>): Program<A> =
    interpretWith(E::class, rule)

/**
 * Handle and remove every effect of family [E], threading state [S] from
 * [initialState] through every step; [transformDone] sees the final state and value.
 * `resume` takes the next state and the response, and is the rest of the program as in
 * [interpret], with the same forms and the same lazy application.
 */
public inline fun <reified E : Effect<*>, S, A, B> Program<A>.interpretS(
    initialState: S,
    noinline transformDone: (S, A) -> Program<B>,
    noinline rule: (S, E, (S, Any?) -> Program<B>) -> Program<B>,
): Program<B> = interpretSWith(E::class, initialState, transformDone, rule)

/**
 * Handle and remove every effect of family [E], threading state [S] from
 * [initialState]; `resume` takes the next state and the response, and is the rest of
 * the program as in [interpret], with the same forms and the same lazy application.
 */
public inline fun <reified E : Effect<*>, S, A> Program<A>.interpretS(
    initialState: S,
    noinline rule: (S, E, (S, Any?) -> Program<A>) -> Program<A>,
): Program<A> = interpretSWith(E::class, initialState, rule)

/** Extract the final value of a fully handled program, or throw naming the unhandled effect. */
public fun <A> Program<A>.runOrThrow(): A =
    when (val forced = force()) {
        is Program.Done -> {
            forced.value
        }

        is Program.Suspended<*, *> -> {
            error("Unhandled effect: ${forced.effect}")
        }

        is Program.Defer, is Program.Lazy -> {
            // unreachable: force() leaves neither
            forced.runOrThrow()
        }
    }

/**
 * Run a fully handled program, capturing the outcome as a [Result]: the final value on
 * success, or an [IllegalStateException] naming the unhandled effect on failure.
 */
public fun <A> Program<A>.run(): Result<A> =
    when (val forced = force()) {
        is Program.Done -> {
            Result.success(forced.value)
        }

        is Program.Suspended<*, *> -> {
            Result.failure(IllegalStateException("Unhandled effect: ${forced.effect}"))
        }

        is Program.Defer, is Program.Lazy -> {
            // unreachable: force() leaves neither
            forced.run()
        }
    }

// The "Fast Type-Aligned Queue"
// https://okmij.org/ftp/Haskell/Reflection.html
// This represents the "Pipeline" of functions waiting to be executed.
// Amortization note: append is O(1) and dequeue (the rotation in resume) is
// amortized O(1) under single-shot consumption — each Join is rotated at most
// once before being discarded. A multi-shot handler that resumes the same
// suspension more than once re-pays the rotation per shot; the bound is
// amortized O(1) per shot, not shared across shots.
internal sealed class Pipeline<in Input, out Output> {
    // A single step: one function from the input to the next program. A step that
    // needs per-step state keeps it in fields, so it costs a single object.
    abstract class Step<in Input, out Output> : Pipeline<Input, Output>() {
        abstract fun run(input: Input): Program<Output>
    }

    // A step backed by a function value
    class FnStep<Input, Output>(
        private val fn: (Input) -> Program<Output>,
    ) : Step<Input, Output>() {
        override fun run(input: Input): Program<Output> = fn(input)
    }

    // Concatenation of two pipelines
    class Join(
        val left: Pipeline<Erased, Erased>,
        val right: Pipeline<Erased, Erased>,
    ) : Pipeline<Erased, Erased>()

    // O(1) Append: Adds a step to the end of the pipeline
    fun <NewOutput> then(step: Step<Output, NewOutput>): Pipeline<Input, NewOutput> {
        val nextStep = step as Pipeline<Erased, Erased>
        val current = this as Pipeline<Erased, Erased>
        return Join(current, nextStep) as Pipeline<Input, NewOutput>
    }
}

/**
 * A request that expects a response of type [R]. Effect families are the open union of
 * everything a [Program] can ask its handlers to do. Handlers match on the erased
 * class, so declare a family as a sealed interface and handle it star-projected
 * (`handle<MyEffect<*>, A>`).
 */
public interface Effect<out R>

/**
 * A pure, reusable description of a computation producing [A]. Build one with [program]
 * or [perform]/[flatMap], then apply handlers ([handle], [intercept], [handleS],
 * [interpret]) and extract the value with [runOrThrow] or [run].
 *
 * A program performs effects; it does not execute them. Each handler applied to it answers
 * one effect family and returns a new program, still a description, with those effects
 * gone. Nothing runs until [run], [runOrThrow] or an edge runner such as `io()` forces the
 * result, and forcing changes nothing, so one value can be interpreted any number of
 * times, under different handler stacks.
 *
 * ## When to use it
 *
 * Take a refund flow: load the order, call the payment provider, write a ledger entry,
 * notify the customer. As a program it is one value, and the caller decides how it runs.
 * Production applies the real handlers. A test applies an in-memory store and a scripted
 * provider that fails on the second call, then asserts on the exact sequence of effects
 * performed, with no mocking framework and no interfaces to maintain. A dry run swaps in a
 * handler that returns a planned provider reference without calling anyone and collects
 * the ledger write instead of executing it, so the entry that would have been written
 * comes back as data. An audit is an [intercept] applied in production only, logging every
 * provider call before proceeding, which the refund code never learns about. Rollback
 * policy is a call-site choice: handle failures outside the ledger handler and a failed
 * notification rolls the write back, handle them inside and the write survives.
 *
 * A `suspend` function with injected dependencies gives the first two. The rest need flags
 * and wrappers threaded through every call, or cannot be expressed at all, because a
 * `suspend` function's effects happen as it runs and cannot be reordered or withheld
 * afterwards. A program is pure overhead for tight loops over millions of trivial effects,
 * or for code that only needs sequencing; its place is a flow with a handful of effects
 * per run that must be tested, previewed, audited or replayed.
 *
 * ## Handlers
 *
 * A handler dispatches on the erased class of the effect: `handle<Fetch<String>, A>` also
 * matches a `Fetch<Int>`, and the mismatch surfaces as a `ClassCastException` where the
 * value is used, so handle the star-projected family (`Fetch<*>`). Responses are not
 * checked against the effect's declared response type either: nothing ties a rule's return
 * value to it, and a wrong response fails at the value's use site. The one response the
 * handlers reject at once is a Program returned from [handle], [intercept] or [handleS],
 * usually a sub-program missing its `.bind()`; [interpret] is the form for a rule that
 * means to return a program. Watch for rules whose last expression is not the intended
 * response, such as a collection mutator returning `Boolean` for a `Unit` effect.
 *
 * Handlers are applied inside out: the handler applied first sees an effect first, and an
 * effect that a handler performs itself (a logging [intercept], a fallback of
 * [recover][com.benjaminearley.freer.effects.recover]) is seen only by the handlers applied
 * after it. Where handlers interact, their order decides the semantics; the KDoc of
 * [attempt][com.benjaminearley.freer.effects.attempt] and
 * [transactional][com.benjaminearley.freer.effects.transactional] gives the cases.
 *
 * ## Exceptions
 *
 * Exceptions are not part of the model. An exception thrown while a program is evaluated,
 * by a handler rule, an `IO` thunk, a [program] block or a [flatMap] function, propagates
 * straight out of the interpretation: a `try` around a `.bind()` inside a block cannot
 * catch it, `finally` blocks in the abandoned program do not run, and [run] does not catch
 * it either, since its `Result` captures only an unhandled effect. Recoverable failures are
 * effects: [raise][com.benjaminearley.freer.effects.raise],
 * [attempt][com.benjaminearley.freer.effects.attempt] and
 * [recover][com.benjaminearley.freer.effects.recover].
 *
 * ## Thread safety
 *
 * A Program is immutable, and so is everything the library puts inside one: the
 * continuations, the binds queued on them, the handlers applied so far and the captured
 * bodies of `program { }` blocks. Building, combining and interpreting a program writes
 * nothing to it and caches nothing in it, so one Program value, handled or not, may be
 * shared between threads and interpreted by any number of them at the same time. It needs
 * no synchronization of its own; publish it as you would any immutable object.
 *
 * Everything an interpretation needs it creates for itself when a terminal operation
 * ([run], [runOrThrow], `io()`) forces the program: the coroutines behind the blocks and
 * rule blocks it runs, one loop per applied handler with its continuation bookkeeping, and
 * the state of every [handleS] and [interpretS], started afresh from the initial value. All
 * of it belongs to that one interpretation and is discarded when it finishes; none of it is
 * visible to another interpretation, so two threads interpreting one value get what they
 * would get from two copies of it. An interpretation driven by `io()` may move between
 * threads at every thunk, as its dispatcher decides, and stays confined all the same, since
 * the dispatcher orders each hand-off.
 *
 * What is not confined is the code a program carries. Handler rules, [flatMap] functions,
 * block bodies and `IO` thunks run on the interpreting thread, once per interpretation, and
 * whatever they capture is shared by every interpretation of that program. A rule that
 * mutates a captured counter, collection or map, or that mutates the initial state of a
 * [handleS] in place instead of returning a new one, makes the program share mutable state,
 * which then needs the synchronization any shared mutable state needs. State threaded by
 * value through [handleS] or [interpretS] is confined.
 *
 * A continuation that outlives its interpretation, such as the `resume` of a yielded
 * generator or a `resume` an [interpret] rule keeps for later, continues that interpretation
 * on whichever thread invokes it, and is handed between threads like any other object. It
 * is immutable data too: a multi-shot continuation may be resumed from several threads at
 * once. The single-shot continuation of a block may not: its check catches a second
 * resumption in sequence, not a concurrent one.
 */
public sealed class Program<out A> {
    /** A finished program holding its final [value]; it performs no effects. */
    public class Done<out A>(
        public val value: A,
    ) : Program<A>() {
        override fun toString(): String = "Done($value)"
    }

    // Impure: The program is paused (Suspended), waiting for a Request to be handled
    internal class Suspended<Response, out A>(
        val effect: Effect<Response>,
        val pipeline: Pipeline<Response, A>, // The continuation logic
    ) : Program<A>()

    // Lazy: a description that is (re)built on demand by build(). Interpreting builds
    // it, so the same Program value can be interpreted any number of times — each
    // interpretation gets a fresh underlying structure. This is what makes program{}
    // blocks pure, reusable descriptions despite being backed by one-shot coroutine
    // state machines. The continuation a rule resumes is one too: built, it runs the
    // rest of the interpretation under that rule's handler.
    internal abstract class Lazy<out A> : Program<A>() {
        abstract fun build(): Program<Erased>
    }

    // A lazy program from a function.
    internal class Thunk<out A>(
        private val thunk: () -> Program<Erased>,
    ) : Lazy<A>() {
        override fun build(): Program<Erased> = thunk()
    }

    // Binds queued behind a lazy program: flatMap/map on one append here in O(1)
    // instead of nesting a closure per bind (closure nesting consumes stack
    // proportional to chain length when forced). force() drains the queue through the
    // built program.
    internal class Defer<out A>(
        val pending: Pipeline<Erased, Erased>,
        val inner: Lazy<*>,
    ) : Program<A>()

    internal companion object {
        val DONE_UNIT: Program<Unit> = Done(Unit)
    }
}

// Build Lazy nodes — draining the binds queued on them — until a concrete
// Done/Suspended program emerges.
internal fun <A> Program<A>.force(): Program<A> {
    Nesting.enter()
    try {
        var current: Program<Erased> = this
        var pending: Pipeline<Erased, Erased>? = null
        while (true) {
            when (val c = current) {
                is Program.Defer -> {
                    // c is nested inside the Defers seen so far, so its queued binds
                    // apply before the ones already accumulated.
                    pending = if (pending == null) c.pending else Pipeline.Join(c.pending, pending)
                    current = c.inner
                }

                is Program.Lazy -> current = c.build()

                is Program.Done -> {
                    val p = pending ?: return c as Program<A>
                    pending = null
                    current = resume(p, c.value)
                }

                is Program.Suspended<*, *> -> {
                    val suspended = c as Program.Suspended<Erased, Erased>
                    val p = pending ?: return c as Program<A>
                    // A bare perform's identity step adds nothing in front of the queued
                    // binds (id-then-p = p), so they take its place.
                    val pipeline =
                        if (suspended.pipeline === IDENTITY_STEP) {
                            p
                        } else {
                            Pipeline.Join(suspended.pipeline, p)
                        }
                    return Program.Suspended(suspended.effect, pipeline) as Program<A>
                }

            }
        }
    } finally {
        Nesting.exit()
    }
}

// Non-inline entry points behind interpret/interpretS, so that the reified wrappers
// inline nothing but a KClass lookup. Applying a handler builds a description; the
// interpreter loop runs when a terminal operation forces it.
@PublishedApi
internal fun <E : Effect<*>, A, B> Program<A>.interpretWith(
    type: KClass<E>,
    transformDone: (A) -> Program<B>,
    rule: (E, (Erased) -> Program<B>) -> Program<B>,
): Program<B> = Program.Thunk { interpreterLoop(this, type, transformDone, rule) }

@PublishedApi
internal fun <E : Effect<*>, A> Program<A>.interpretWith(
    type: KClass<E>,
    rule: (E, (Erased) -> Program<A>) -> Program<A>,
): Program<A> = interpretWith(type, { Program.Done(it) }, rule)

@PublishedApi
internal fun <E : Effect<*>, S, A, B> Program<A>.interpretSWith(
    type: KClass<E>,
    initialState: S,
    transformDone: (S, A) -> Program<B>,
    rule: (S, E, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> = Program.Thunk { interpreterLoopS(this, initialState, type, transformDone, rule) }

@PublishedApi
internal fun <E : Effect<*>, S, A> Program<A>.interpretSWith(
    type: KClass<E>,
    initialState: S,
    rule: (S, E, (S, Erased) -> Program<A>) -> Program<A>,
): Program<A> = interpretSWith(type, initialState, { _, a -> Program.Done(a) }, rule)

/**
 * Sequence [f] after this program. Evaluation timing follows the receiver: on a
 * [Program.Done] value, [f] runs immediately at construction; on a suspended or
 * deferred program it is queued and runs during interpretation.
 */
public fun <A, B> Program<A>.flatMap(f: (A) -> Program<B>): Program<B> =
    if (this is Program.Done) f(this.value) else then(Pipeline.FnStep(f))

/**
 * Transform this program's final value with [f]. Like [flatMap], [f] runs immediately
 * on a [Program.Done] value and during interpretation otherwise.
 */
public fun <A, B> Program<A>.map(f: (A) -> B): Program<B> =
    if (this is Program.Done) Program.Done(f(this.value)) else then(Pipeline.FnStep { Program.Done(f(it)) })

// Queue [step] behind this program: a Done value runs it at once; a suspended or
// deferred program appends it to its pipeline without forcing.
internal fun <A, B> Program<A>.then(step: Pipeline.Step<A, B>): Program<B> =
    when (this) {
        is Program.Done -> {
            step.run(this.value)
        }

        is Program.Suspended<*, *> -> {
            val suspended = this as Program.Suspended<Erased, A>
            // Binding on a fresh perform: replace the identity step with the new step
            // directly (id-then-f = f) instead of allocating Join(IDENTITY_STEP, step).
            val pipeline =
                if (suspended.pipeline === IDENTITY_STEP) {
                    step as Pipeline<Erased, B>
                } else {
                    suspended.pipeline.then(step)
                }
            Program.Suspended(suspended.effect, pipeline)
        }

        is Program.Defer -> {
            Program.Defer(pending.then(step as Pipeline.Step<Erased, Erased>), inner)
        }

        is Program.Lazy -> {
            Program.Defer(step as Pipeline<Erased, Erased>, this)
        }
    }

private val IDENTITY_STEP =
    Pipeline.FnStep<Erased, Erased> {
        if (it == Unit) Program.DONE_UNIT else Program.Done(it)
    }

/** Perform an effect: a one-step program that suspends on [effect] and produces its response. */
public fun <R> perform(effect: Effect<R>): Program<R> = Program.Suspended(effect, IDENTITY_STEP as Pipeline<R, R>)

// The Virtual Machine
// This function advances the pipeline by one step.
internal tailrec fun <A, B> resume(
    pipeline: Pipeline<A, B>,
    input: A,
): Program<B> =
    when (pipeline) {
        is Pipeline.Step -> {
            pipeline.run(input)
        }

        is Pipeline.Join -> {
            val left = pipeline.left
            val right = pipeline.right

            if (left is Pipeline.Step) {
                // Left is a single step: Run it.
                val leftStep = left as Pipeline.Step<A, Erased>

                when (val result = leftStep.run(input)) {
                    is Program.Done -> {
                        // Step finished cleanly: Feed result into the Right side
                        resume(right as Pipeline<Erased, B>, result.value)
                    }

                    is Program.Defer -> {
                        // Step returned a lazy program. Invariant: a lazy program is never
                        // built from inside a step or a bind — only force() and the
                        // interpreter loops build, and they loop. Building here would nest
                        // one JVM frame per level of non-tail bind recursion (each level's
                        // thunk reaches this point again). Instead, queue the Right side
                        // behind the binds already pending and hand it back to the enclosing
                        // loop, which drains the whole chain iteratively.
                        Program.Defer<B>(Pipeline.Join(result.pending, right), result.inner)
                    }

                    is Program.Lazy -> {
                        Program.Defer<B>(right, result)
                    }

                    is Program.Suspended<*, *> -> {
                        // Step suspended: We must attach the Right side to the new suspension.
                        // Concatenate the queues directly instead of wrapping 'right' in a
                        // closure that calls resume: nested closures grow the stack O(depth)
                        // for non-tail-recursive programs; a Join keeps the unwind iterative
                        // via the rotation above.
                        val suspended = result as Program.Suspended<Erased, Erased>
                        // A bare perform's identity step adds nothing in front of the
                        // Right side (id-then-right = right), so Right takes its place.
                        val newQueue =
                            if (suspended.pipeline === IDENTITY_STEP) {
                                right
                            } else {
                                Pipeline.Join(suspended.pipeline, right)
                            }
                        Program.Suspended(suspended.effect, newQueue) as Program<B>
                    }
                }
            } else {
                // Left is a Join: Rotate Right to maintain performance guarantees.
                // ( (A + B) + C ) -> ( A + (B + C) )
                val leftJoin = left as Pipeline.Join
                val newLeft = leftJoin.left
                val newRight = Pipeline.Join(leftJoin.right, right)
                resume(Pipeline.Join(newLeft, newRight) as Pipeline<A, B>, input)
            }
        }
    }

// Stack-safe Generic Interpreter Loop
// A rule's resume call builds the rest of the program as a lazy value and runs
// nothing. When the rule returns that value, the loop runs the continuation itself
// and iterates, so a chain of such rules stays on one stack frame. Any other program
// the rule returns, including one built from the continuation with flatMap or a
// block, goes back to the enclosing force() loop, which invokes the lazy program
// there: that re-enters this loop one level down for the next matching effect,
// whose result returns the same way, so every form of resume runs on a constant
// stack (a lazy program is never built from inside a step or a bind).
internal fun <Target : Effect<*>, A, B> interpreterLoop(
    initialProgram: Program<A>,
    targetClass: KClass<Target>,
    transformDone: (A) -> Program<B>, // Logic for "Pure" values (A -> B)
    rule: (Target, (Erased) -> Program<B>) -> Program<B>, // Logic for "Impure" effects
): Program<B> {
    Nesting.enter()
    try {
        var program: Program<A> = initialProgram

        while (true) {
            when (val current = program) {
                is Program.Done -> {
                    return transformDone(current.value)
                }

                is Program.Defer, is Program.Lazy -> {
                    program = current.force()
                }

                is Program.Suspended<*, *> -> {
                    val suspended = current as Program.Suspended<Erased, A>
                    val effect = suspended.effect
                    val pipeline = suspended.pipeline

                    if (matchesEffect(targetClass, effect)) {
                        // MATCH: We found the effect we are looking for.
                        val resumer = Resumer(pipeline, targetClass, transformDone, rule)
                        val result = rule(effect as Target, resumer)
                        resumer.direct = false // Later calls build lazy programs of their own
                        // The rule returned the continuation it was given: run it here.
                        val response =
                            when {
                                result === resumer -> resumer.response
                                result is Resumed<*> && result.owner === resumer -> result.response
                                else -> return result
                            }
                        program = resume(pipeline, response)
                    } else {
                        // NO MATCH: Relay the effect.
                        // The continuation waits for the response, then re-enters this loop.
                        return Program.Suspended(effect, RelayStep(pipeline, targetClass, transformDone, rule))
                    }
                }
            }
        }
    } finally {
        Nesting.exit()
    }
}

// The continuation of an effect interpreterLoop relays past: feeds the response to
// the inner pipeline and re-enters the loop. One object per relayed effect, holding
// the loop's parameters as fields rather than as a closure wrapped in a step.
private class RelayStep<Target : Effect<*>, A, B>(
    private val pipeline: Pipeline<Erased, A>,
    private val targetClass: KClass<Target>,
    private val transformDone: (A) -> Program<B>,
    private val rule: (Target, (Erased) -> Program<B>) -> Program<B>,
) : Pipeline.Step<Erased, B>() {
    override fun run(input: Erased): Program<B> =
        interpreterLoop(resume(pipeline, input), targetClass, transformDone, rule)
}

// The continuation interpreterLoop hands to a rule, and the lazy program its first call
// returns: that call stores its response here instead of allocating, and the loop
// recognises the object when the rule returns it. Later calls, and every call after the
// rule has returned, get a Resumed of their own, so a continuation kept past its rule is
// immutable and can be resumed from any thread. One object per matched effect.
private class Resumer<Target : Effect<*>, A, B>(
    private val pipeline: Pipeline<Erased, A>,
    private val targetClass: KClass<Target>,
    private val transformDone: (A) -> Program<B>,
    private val rule: (Target, (Erased) -> Program<B>) -> Program<B>,
) : Program.Lazy<B>(),
    (Erased) -> Program<B> {
    // The response of the call this object stands for.
    var response: Erased = null
    private var handedOut = false

    // True while the rule is running; the loop clears it once the rule returns.
    var direct = true

    override fun invoke(response: Erased): Program<B> {
        if (!direct || handedOut) return Resumed(this, response)
        handedOut = true
        this.response = response
        return this
    }

    // Built by force(): the rest of the interpretation from this continuation on.
    override fun build(): Program<Erased> = run(response)

    fun run(response: Erased): Program<Erased> = interpreterLoop(resume(pipeline, response), targetClass, transformDone, rule)
}

// A further resumption of a continuation, immutable; run when forced.
private class Resumed<B>(
    val owner: Resumer<*, *, B>,
    val response: Erased,
) : Program.Lazy<B>() {
    override fun build(): Program<Erased> = owner.run(response)
}

// Stack-safe Stateful Interpreter Loop
// As interpreterLoop, but threads state S through every step.
internal fun <Target : Effect<*>, S, A, B> interpreterLoopS(
    initialProgram: Program<A>,
    initialState: S,
    targetClass: KClass<Target>,
    transformDone: (S, A) -> Program<B>,
    rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> {
    Nesting.enter()
    try {
        var program: Program<A> = initialProgram
        var state = initialState

        while (true) {
            when (val current = program) {
                is Program.Done -> {
                    return transformDone(state, current.value)
                }

                is Program.Defer, is Program.Lazy -> {
                    program = current.force()
                }

                is Program.Suspended<*, *> -> {
                    val suspended = current as Program.Suspended<Erased, A>
                    val effect = suspended.effect
                    val pipeline = suspended.pipeline

                    if (matchesEffect(targetClass, effect)) {
                        val resumer = ResumerS(pipeline, targetClass, transformDone, rule)
                        val result = rule(state, effect as Target, resumer)
                        resumer.direct = false
                        val response: Erased
                        when {
                            result === resumer -> {
                                response = resumer.response
                                state = resumer.state as S
                            }

                            result is ResumedS<*> && result.owner === resumer -> {
                                response = result.response
                                state = result.state as S
                            }

                            else -> return result
                        }
                        program = resume(pipeline, response)
                    } else {
                        // Capture the state as of this relay, not a mutable reference: a
                        // multi-shot outer handler resumes this continuation several times,
                        // and each shot must re-enter with the state as it was here. This
                        // capture is what makes per-branch (backtracking) state work when a
                        // stateful handler sits inside a multi-shot one.
                        return Program.Suspended(effect, RelayStepS(pipeline, state, targetClass, transformDone, rule))
                    }
                }
            }
        }
    } finally {
        Nesting.exit()
    }
}

// The continuation of an effect interpreterLoopS relays past: RelayStep plus the
// state captured at the relay, which every re-entry starts from.
private class RelayStepS<Target : Effect<*>, S, A, B>(
    private val pipeline: Pipeline<Erased, A>,
    private val state: S,
    private val targetClass: KClass<Target>,
    private val transformDone: (S, A) -> Program<B>,
    private val rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
) : Pipeline.Step<Erased, B>() {
    override fun run(input: Erased): Program<B> =
        interpreterLoopS(resume(pipeline, input), state, targetClass, transformDone, rule)
}

// The continuation interpreterLoopS hands to a rule: Resumer plus the state to go on
// with.
private class ResumerS<Target : Effect<*>, S, A, B>(
    private val pipeline: Pipeline<Erased, A>,
    private val targetClass: KClass<Target>,
    private val transformDone: (S, A) -> Program<B>,
    private val rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
) : Program.Lazy<B>(),
    (S, Erased) -> Program<B> {
    var response: Erased = null
    var state: Any? = null
    private var handedOut = false
    var direct = true

    override fun invoke(
        newState: S,
        response: Erased,
    ): Program<B> {
        if (!direct || handedOut) return ResumedS(this, newState, response)
        handedOut = true
        state = newState
        this.response = response
        return this
    }

    override fun build(): Program<Erased> = run(state, response)

    fun run(
        state: Any?,
        response: Erased,
    ): Program<Erased> = interpreterLoopS(resume(pipeline, response), state as S, targetClass, transformDone, rule)
}

private class ResumedS<B>(
    val owner: ResumerS<*, *, *, B>,
    val state: Any?,
    val response: Erased,
) : Program.Lazy<B>() {
    override fun build(): Program<Erased> = owner.run(state, response)
}
