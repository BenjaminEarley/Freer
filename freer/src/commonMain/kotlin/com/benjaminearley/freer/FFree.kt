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
 * [transformDone]. [rule] receives the effect and a `resume` continuation; a direct
 * `resume` call inside the rule may happen at most once and its result must be
 * returned unchanged (the interpreter trampolines it and throws on misuse). To
 * transform the result, call `resume` from a deferred context such as inside
 * `program { }` or a `flatMap` on a non-`Done` program. Applied lazily.
 */
public inline fun <reified E : Effect<*>, A, B> Program<A>.interpret(
    noinline transformDone: (A) -> Program<B>,
    noinline rule: (E, (Any?) -> Program<B>) -> Program<B>,
): Program<B> = lazyInterpret(this, E::class, transformDone, rule)

/**
 * Handle and remove every effect of family [E]. A direct `resume` call inside [rule]
 * may happen at most once and its result must be returned unchanged (the interpreter
 * trampolines it and throws on misuse); to transform the result, call `resume` from a
 * deferred context such as inside `program { }` or a `flatMap` on a non-`Done`
 * program. The handler is applied lazily, when the program is run.
 */
public inline fun <reified E : Effect<*>, A> Program<A>.interpret(noinline rule: (E, (Any?) -> Program<A>) -> Program<A>): Program<A> =
    interpret(
        transformDone = { Program.Done(it) },
        rule = rule,
    )

/**
 * Handle and remove every effect of family [E], threading state [S] from
 * [initialState] through every step; [transformDone] sees the final state and value.
 * `resume` takes the next state and the response. The rule contract and lazy
 * application of [interpret] apply.
 */
public inline fun <reified E : Effect<*>, S, A, B> Program<A>.interpretS(
    initialState: S,
    noinline transformDone: (S, A) -> Program<B>,
    noinline rule: (S, E, (S, Any?) -> Program<B>) -> Program<B>,
): Program<B> = lazyInterpretS(this, initialState, E::class, transformDone, rule)

/**
 * Handle and remove every effect of family [E], threading state [S] from
 * [initialState]; `resume` takes the next state and the response. The rule contract
 * and lazy application of [interpret] apply.
 */
public inline fun <reified E : Effect<*>, S, A> Program<A>.interpretS(
    initialState: S,
    noinline rule: (S, E, (S, Any?) -> Program<A>) -> Program<A>,
): Program<A> =
    interpretS(
        initialState = initialState,
        transformDone = { _, a -> Program.Done(a) },
        rule = rule,
    )

/** Extract the final value of a fully handled program, or throw naming the unhandled effect. */
public fun <A> Program<A>.runOrThrow(): A =
    when (val forced = force()) {
        is Program.Done -> {
            forced.value
        }

        is Program.Suspended<*, *> -> {
            error("Unhandled effect: ${forced.effect}")
        }

        is Program.Defer -> {
            // unreachable: force() removes Defer
            forced.runOrThrow()
        }

        is Program.Bounce -> {
            trampolineMisuse()
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

        is Program.Defer -> {
            // unreachable: force() removes Defer
            forced.run()
        }

        is Program.Bounce -> {
            trampolineMisuse()
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
 */
public sealed class Program<out A> {
    /** A finished program holding its final [value]; it performs no effects. */
    public data class Done<out A>(
        val value: A,
    ) : Program<A>()

    // Impure: The program is paused (Suspended), waiting for a Request to be handled
    internal class Suspended<Response, out A>(
        val effect: Effect<Response>,
        val pipeline: Pipeline<Response, A>, // The continuation logic
    ) : Program<A>()

    // Lazy: The program is a description that is (re)built on demand. Interpreting
    // forces the thunk, so the same Program value can be interpreted any number of
    // times — each interpretation gets a fresh underlying structure. This is what
    // makes program{} blocks pure, reusable descriptions despite being backed by
    // one-shot coroutine state machines.
    // pending queues binds appended after this Defer was built: flatMap/map on an
    // unforced program append here in O(1) instead of nesting a closure per bind
    // (closure nesting consumes stack proportional to chain length when forced).
    // force() drains it through the thunk's result.
    internal class Defer<out A>(
        val pending: Pipeline<Erased, Erased>? = null,
        val thunk: () -> Program<Erased>,
    ) : Program<A>()

    // Internal trampoline marker: returned by a direct resume() call inside the
    // interpreter loops. Never a real program value — any attempt to use it as one
    // is a broken rule contract (see interpret) and fails with trampolineMisuse().
    internal data object Bounce : Program<Nothing>()

    internal companion object {
        val DONE_UNIT: Program<Unit> = Done(Unit)
    }
}

// Unwrap Defer nodes — draining any binds queued on them — until a concrete
// Done/Suspended program emerges.
@PublishedApi
internal fun <A> Program<A>.force(): Program<A> {
    var current: Program<Erased> = this
    var pending: Pipeline<Erased, Erased>? = null
    while (true) {
        when (val c = current) {
            is Program.Defer -> {
                val own = c.pending
                if (own != null) {
                    // c is nested inside the Defers seen so far, so its queued
                    // binds apply before the ones already accumulated.
                    pending = if (pending == null) own else Pipeline.Join(own, pending)
                }
                current = c.thunk()
            }

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

            is Program.Bounce -> trampolineMisuse()
        }
    }
}

// Lazy entry points for interpret/interpretS: applying a handler builds a description;
// the interpreter loop runs when a terminal operation forces it.
@PublishedApi
internal fun <Target : Effect<*>, A, B> lazyInterpret(
    initialProgram: Program<A>,
    targetClass: KClass<Target>,
    transformDone: (A) -> Program<B>,
    rule: (Target, (Erased) -> Program<B>) -> Program<B>,
): Program<B> = Program.Defer { interpreterLoop(initialProgram, targetClass, transformDone, rule) }

@PublishedApi
internal fun <Target : Effect<*>, S, A, B> lazyInterpretS(
    initialProgram: Program<A>,
    initialState: S,
    targetClass: KClass<Target>,
    transformDone: (S, A) -> Program<B>,
    rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> = Program.Defer { interpreterLoopS(initialProgram, initialState, targetClass, transformDone, rule) }

internal fun trampolineMisuse(): Nothing =
    throw IllegalStateException(
        "Broken rule contract: the Program returned by a direct resume() call is an internal " +
            "trampoline marker, not a real value — return it from the rule unchanged. To transform " +
            "or combine continuation results, call resume from a genuinely deferred context " +
            "instead (e.g. bind it inside a program{} block).",
    )

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
            val next = step as Pipeline.Step<Erased, Erased>
            Program.Defer(pending?.then(next) ?: next, thunk)
        }

        is Program.Bounce -> {
            trampolineMisuse()
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
                        // Step returned a lazy program. Invariant: a Defer is never forced
                        // from inside a step or a bind — only force() and the interpreter
                        // loops force, and they loop. Forcing here would nest one JVM frame
                        // per level of non-tail bind recursion (each level's thunk reaches
                        // this point again). Instead, queue the Right side behind the binds
                        // already pending on the Defer and hand it back to the enclosing
                        // loop, which drains the whole chain iteratively.
                        val own = result.pending
                        Program.Defer<B>(if (own == null) right else Pipeline.Join(own, right), result.thunk)
                    }

                    is Program.Bounce -> {
                        trampolineMisuse()
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

internal fun directResumeCalledTwice(): Nothing =
    throw IllegalStateException(
        "Broken rule contract: resume() may be called at most once directly from a rule. " +
            "For multi-shot resumption, call resume from a genuinely deferred context " +
            "(e.g. bind it inside a program{} block).",
    )

internal fun directResumeDiscarded(): Nothing =
    throw IllegalStateException(
        "Broken rule contract: the rule called resume() directly but returned a different " +
            "Program — the resumed continuation would be silently lost. Return the result of " +
            "resume() unchanged, or do not call resume() at all (to short-circuit).",
    )

// Stack-safe Generic Interpreter Loop
// Uses a trampoline: when a rule calls resume() directly and returns the result,
// the continuation returns the internal Bounce marker instead of recursing. The
// while-loop detects it and iterates. When resume() is called from a deferred
// context (e.g. bound inside a program{} block), the continuation re-enters this
// loop recursively — but only one level deep: the nested loop runs until the next
// matching effect, whose rule returns its own deferred program, and then returns
// it unforced. That Defer is drained by the enclosing force() loop (a Defer is
// never forced from inside a step or a bind), so every deferred resume starts
// from the same stack depth instead of nesting one frame per effect. Violations
// of the rule contract (see interpret) fail with IllegalStateException instead
// of corrupting silently.
internal fun <Target : Effect<*>, A, B> interpreterLoop(
    initialProgram: Program<A>,
    targetClass: KClass<Target>,
    transformDone: (A) -> Program<B>, // Logic for "Pure" values (A -> B)
    rule: (Target, (Erased) -> Program<B>) -> Program<B>, // Logic for "Impure" effects
): Program<B> {
    var program: Program<A> = initialProgram

    while (true) {
        when (val current = program) {
            is Program.Done -> {
                return transformDone(current.value)
            }

            is Program.Defer -> {
                program = current.force()
            }

            is Program.Bounce -> {
                trampolineMisuse()
            }

            is Program.Suspended<*, *> -> {
                val suspended = current as Program.Suspended<Erased, A>
                val effect = suspended.effect
                val pipeline = suspended.pipeline

                if (matchesEffect(targetClass, effect)) {
                    // MATCH: We found the effect we are looking for.
                    val resumer = Resumer(pipeline, targetClass, transformDone, rule)
                    val result = rule(effect as Target, resumer)
                    resumer.direct = false // Any future calls to this continuation are deferred

                    val next = resumer.next
                    if (result is Program.Bounce) {
                        program = next ?: trampolineMisuse()
                        continue
                    }
                    if (next != null) directResumeDiscarded()
                    return result
                } else {
                    // NO MATCH: Relay the effect.
                    // The continuation waits for the response, then re-enters this loop.
                    return Program.Suspended(effect, RelayStep(pipeline, targetClass, transformDone, rule))
                }
            }
        }
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

// The continuation interpreterLoop hands to a rule: one object per matched effect,
// holding the trampoline's bookkeeping (`next`, `direct`) as plain fields rather
// than as a lambda plus a box for each captured variable.
private class Resumer<Target : Effect<*>, A, B>(
    private val pipeline: Pipeline<Erased, A>,
    private val targetClass: KClass<Target>,
    private val transformDone: (A) -> Program<B>,
    private val rule: (Target, (Erased) -> Program<B>) -> Program<B>,
) : (Erased) -> Program<B> {
    // The resumed program recorded by a direct call, for the loop to iterate on.
    var next: Program<A>? = null

    // True while the rule is running; the loop clears it once the rule returns, so
    // every later call is a deferred resume.
    var direct = true

    override fun invoke(response: Erased): Program<B> {
        if (direct && next != null) directResumeCalledTwice()
        val resumed = resume(pipeline, response)
        return if (direct) {
            // Called directly by the rule — trampoline
            next = resumed
            Program.Bounce
        } else {
            // Called from a deferred context (e.g. inside flatMap) — recurse normally
            interpreterLoop(resumed, targetClass, transformDone, rule)
        }
    }
}

// Stack-safe Stateful Interpreter Loop (paper's handleRelayS)
// Same trampoline as interpreterLoop, but threads state S through every step.
internal fun <Target : Effect<*>, S, A, B> interpreterLoopS(
    initialProgram: Program<A>,
    initialState: S,
    targetClass: KClass<Target>,
    transformDone: (S, A) -> Program<B>,
    rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> {
    var program: Program<A> = initialProgram
    var state = initialState

    while (true) {
        when (val current = program) {
            is Program.Done -> {
                return transformDone(state, current.value)
            }

            is Program.Defer -> {
                program = current.force()
            }

            is Program.Bounce -> {
                trampolineMisuse()
            }

            is Program.Suspended<*, *> -> {
                val suspended = current as Program.Suspended<Erased, A>
                val effect = suspended.effect
                val pipeline = suspended.pipeline

                if (matchesEffect(targetClass, effect)) {
                    val resumer = ResumerS(pipeline, targetClass, transformDone, rule)
                    val result = rule(state, effect as Target, resumer)
                    resumer.direct = false

                    val next = resumer.next
                    if (result is Program.Bounce) {
                        program = next ?: trampolineMisuse()
                        state = resumer.nextState as S
                        continue
                    }
                    if (next != null) directResumeDiscarded()
                    return result
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

// The continuation interpreterLoopS hands to a rule: Resumer plus the state a
// direct call resumes with.
private class ResumerS<Target : Effect<*>, S, A, B>(
    private val pipeline: Pipeline<Erased, A>,
    private val targetClass: KClass<Target>,
    private val transformDone: (S, A) -> Program<B>,
    private val rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
) : (S, Erased) -> Program<B> {
    var next: Program<A>? = null
    var nextState: Any? = null
    var direct = true

    override fun invoke(
        newState: S,
        response: Erased,
    ): Program<B> {
        if (direct && next != null) directResumeCalledTwice()
        val resumed = resume(pipeline, response)
        return if (direct) {
            next = resumed
            nextState = newState
            Program.Bounce
        } else {
            interpreterLoopS(resumed, newState, targetClass, transformDone, rule)
        }
    }
}
