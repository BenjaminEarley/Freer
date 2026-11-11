@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.reflect.KClass

/**
 * Receiver scope of [program] blocks: sequence sub-programs, and effects, with `bind`.
 * Suspension is restricted, so only binds — not arbitrary suspend calls — can occur inside.
 */
@RestrictsSuspension
public sealed class ProgramScope {
    /**
     * Run this sub-program as the next step of the enclosing block and return its value.
     * A block's continuation is single-shot: binding a multi-shot effect (such as a
     * choice under collectAll) directly in a block fails when it is resumed twice.
     */
    public suspend fun <A> Program<A>.bind(): A =
        suspendCoroutineUninterceptedOrReturn { cont ->
            val program = this@bind
            if (program is Program.Done) {
                // Already a value: hand it straight back without suspending, instead of
                // parking it in pendingProgram/pendingContinuation for buildProgram to
                // resume one step later.
                program.value
            } else {
                suspendOn(program, cont)
            }
        }

    /**
     * Perform this effect as the next step of the enclosing block and return its response.
     * An effect is the one-step program [perform] makes of it, so `effect.bind()` is
     * `perform(effect).bind()`, under the same single-shot rule.
     */
    public suspend fun <R> Effect<R>.bind(): R = perform(this).bind()

    // Park [program] as the pending bind and signal the coroutine behind [cont] to
    // suspend; buildProgram sequences the program and resumes [cont] with its value.
    internal abstract fun suspendOn(
        program: Program<*>,
        cont: Continuation<*>,
    ): Any?
}

// The only ProgramScope, which is also the completion of the coroutine it drives. The
// Continuation supertype lives here rather than on the public type so that block code
// cannot call resumeWith and complete the coroutine behind the builder's back. One
// object per program{} evaluation and per rule invocation.
private class ProgramScopeImpl :
    ProgramScope(),
    Continuation<Any?> {
    override val context: CoroutineContext = EmptyCoroutineContext

    // What the coroutine last signalled, as plain fields rather than one object per
    // signal: a bind parks the bound program and the continuation waiting for its
    // value; the block's return records the final value and marks completion.
    private var pendingProgram: Program<Any?>? = null
    private var pendingContinuation: Continuation<Any?>? = null
    private var completed = false
    private var completedValue: Any? = null

    override fun resumeWith(result: Result<Any?>) {
        completedValue = result.getOrThrow()
        completed = true
    }

    override fun suspendOn(
        program: Program<*>,
        cont: Continuation<*>,
    ): Any? {
        pendingProgram = program
        pendingContinuation = cont as Continuation<Any?>
        return COROUTINE_SUSPENDED
    }

    fun <A> buildProgram(): Program<A> {
        while (true) {
            if (completed) return Program.Done(completedValue as A)
            // Neither completed nor parked on a bind means the coroutine suspended
            // through something other than bind, which the restricted scope rules out.
            val program = pendingProgram!!
            val continuation = pendingContinuation!!
            if (program is Program.Done) {
                // Pure bind: resume in place and keep looping. Recursing here
                // (as BindStep does after an effectful bind) overflows the stack
                // on long chains of already-Done binds.
                continuation.resumeWith(Result.success(program.value))
            } else {
                // Suspended or lazy: queue the continuation behind it without
                // building. Invariant: a lazy program is never built from inside a
                // step or a bind — only force() and the interpreter loops build,
                // and they loop. program{} runs buildProgram inside its own thunk,
                // so building here would nest one JVM frame per level whenever
                // the bound thunk binds another lazy program (nested recursive
                // program{} blocks, or a rule that binds resume). then(step)
                // queues the step in O(1), and the enclosing force() loop drains
                // the chain iteratively.
                return program.then(BindStep(this, continuation)) as Program<A>
            }
        }
    }
}

// The step queued behind a suspended or deferred bind: it hands the bound program's
// value to the parked coroutine and carries on building the program. One object per
// bind, with the single-shot flag as a field.
private class BindStep(
    private val scope: ProgramScopeImpl,
    private val continuation: Continuation<Any?>,
) : Pipeline.Step<Erased, Erased>() {
    private var consumed = false

    override fun run(input: Erased): Program<Erased> {
        // The coroutine behind this continuation is single-shot; guard against a
        // second resumption, which would silently re-run only the code after the
        // last bind with stale locals.
        check(!consumed) {
            "program{} continuations are single-shot: this suspension was " +
                "already resumed. Multi-shot resumption requires hand-built " +
                "programs (perform/flatMap chains)."
        }
        consumed = true
        continuation.resumeWith(Result.success(input))
        return scope.buildProgram()
    }
}

/**
 * Build a [Program] in direct style: `.bind()` each sub-program, or each effect, instead
 * of chaining flatMap. The block is captured, not run — each interpretation forces it afresh, so
 * program{} values are pure, replayable descriptions that can be interpreted any number
 * of times, under different handler stacks.
 */
public fun <A> program(block: suspend ProgramScope.() -> A): Program<A> =
    Program.Thunk {
        val scope = ProgramScopeImpl()
        block.createCoroutineUnintercepted(receiver = scope, completion = scope).resume(Unit)
        scope.buildProgram()
    }

// Run a rule block eagerly and continue with [onValue] on its result. A block that
// returns without suspending hands its value over directly, with no completion round
// trip and no Program wrapper; one that suspends is built into a concrete
// Done/Suspended program — program(block).force() without the intermediate thunk —
// and [onValue] is queued behind it. Handler rules force immediately, so nothing
// needs the laziness; program{} keeps it for users. When the rule returns without
// suspending, the objects this costs per effect are the scope and a copy of the rule
// lambda, and whether they are allocated at all is up to the JIT's escape analysis:
// on JDK 21 both are eliminated and such a rule costs nothing over a direct resume,
// on JDK 17 they are allocated, 72 bytes per effect, with the same inlining decisions
// on both. There is deliberately no non-suspend fast-path overload: it would double
// the API for a cost the newer JIT already removes.
private inline fun <A, B> runRuleBlock(
    noinline block: suspend ProgramScope.() -> A,
    crossinline onValue: (A) -> Program<B>,
): Program<B> {
    val scope = ProgramScopeImpl()
    val immediate = block.startCoroutineUninterceptedOrReturn(receiver = scope, completion = scope)
    return if (immediate === COROUTINE_SUSPENDED) {
        scope.buildProgram<A>().force().flatMap { onValue(it) }
    } else {
        onValue(immediate as A)
    }
}

// Rule responses for handle, intercept and handleS are plain values. A Program response
// almost always means a sub-program was returned without .bind(), which would otherwise
// drop its effects silently (Unit effects) or fail later at the use site (value effects).
private fun checkResponse(
    effect: Any,
    response: Any?,
): Any? {
    if (response is Program<*>) programResponseError(effect)
    return response
}

private fun programResponseError(effect: Any): Nothing {
    val name = effect::class.simpleName ?: effect.toString()
    throw IllegalStateException(
        "Rule for $name returned a Program instead of a response. Missing .bind()? " +
            "handle, intercept and handleS responses are plain values; use interpret to return a program.",
    )
}

/**
 * Interpret with auto-resume: the block's return value is the effect's response. For an
 * effect that returns `Unit`, do the work; for one that returns a value, return it. The
 * block may bind other programs, which run in place of the effect as in a [program] block,
 * so a rule that translates its effect into I/O binds a `performIO { }`. The block is a
 * one-shot coroutine: under a multi-shot handler such as `collectAll`, a block that binds
 * the choice fails when resumed a second time, while one that returns without suspending
 * is fine.
 *
 * The response is not checked against the effect's declared type, except that a [Program]
 * is rejected at once with an `IllegalStateException` naming the effect, since it almost
 * always means a sub-program was returned without its `.bind()`. Applied lazily, when the
 * program is run.
 */
public inline fun <reified E : Effect<*>, A> Program<A>.handle(noinline rule: suspend ProgramScope.(E) -> Any?): Program<A> =
    handleWith(E::class, rule)

// Non-inline entry point behind handle, so that the reified wrapper inlines nothing but
// a KClass lookup.
@PublishedApi
internal fun <E : Effect<*>, A> Program<A>.handleWith(
    type: KClass<E>,
    rule: suspend ProgramScope.(E) -> Any?,
): Program<A> =
    interpretWith(type) { op, resume ->
        runRuleBlock({ rule(op) }) { response -> resume(checkResponse(op, response)) }
    }

// The proceed() continuation handed to an intercept rule: re-emits the intercepted
// effect and yields the downstream response, exactly as `effect.bind()`
// would. A class rather than a suspend lambda capturing the effect: the object holds
// only the effect reference, and suspending through the intrinsic directly needs no
// state machine, whereas a suspend lambda is copied together with its continuation
// fields on every invocation. It cannot call bind() itself — a suspend function
// outside the restricted scope may not — so it parks the program via suspendOn.
private class Proceed(
    private val effect: Effect<*>,
) : suspend (ProgramScope) -> Any? {
    override suspend fun invoke(scope: ProgramScope): Any? =
        suspendCoroutineUninterceptedOrReturn { cont -> scope.suspendOn(perform(effect), cont) }
}

/**
 * Middleware: observe an effect without consuming it. `proceed()` re-emits the effect to
 * the handler downstream and returns its response, so the rule decides where the effect
 * executes and may inspect, rewrite or replace the result; a rule that answers without
 * calling `proceed()` consumes the effect. Effects the rule performs itself are seen only
 * by the handlers applied after this one, so a middleware that logs needs the log handler
 * downstream of it.
 *
 * Each intercepted effect is handled twice, and the rule's coroutine is parked in between,
 * which costs a few times a plain [handle]; keep it off hot paths. The cheaper form, and
 * the one that works under a multi-shot handler, is an [interpret] rule that re-emits the
 * effect and continues from a [flatMap]:
 * `interpret<NonDet<*>, A> { op, resume -> perform(op).flatMap { resume(it) } }`. Under a
 * multi-shot handler an intercept on the same effect fails, because `proceed()` suspends
 * the one-shot rule block and the downstream handler resumes it once per option; an
 * intercept on an unrelated effect is fine. Applied lazily, when the program is run.
 */
public inline fun <reified E : Effect<*>, A> Program<A>.intercept(
    noinline rule: suspend ProgramScope.(effect: E, proceed: suspend ProgramScope.() -> Any?) -> Any?,
): Program<A> = interceptWith(E::class, rule)

// Non-inline entry point behind intercept, so that the reified wrapper inlines nothing
// but a KClass lookup.
@PublishedApi
internal fun <E : Effect<*>, A> Program<A>.interceptWith(
    type: KClass<E>,
    rule: suspend ProgramScope.(effect: E, proceed: suspend ProgramScope.() -> Any?) -> Any?,
): Program<A> =
    interpretWith(type) { op, resume ->
        val proceed = Proceed(op)
        runRuleBlock({ rule(op, proceed) }) { response -> resume(checkResponse(op, response)) }
    }

/**
 * Stateful [handle]: the block receives the current state and returns `newState to response`.
 * Mind the pair order when the state and the response share a type — `response to newState`
 * is equally well-typed and silently swaps the two. The state is threaded by value, so each
 * interpretation starts from [initialState] and shares nothing with another; a rule that
 * mutates the initial state in place instead of returning a new one shares it between
 * every interpretation of the program.
 */
public inline fun <reified E : Effect<*>, S, A> Program<A>.handleS(
    initialState: S,
    noinline rule: suspend ProgramScope.(S, E) -> Pair<S, Any?>,
): Program<A> = handleSWith(E::class, initialState, rule)

// Non-inline entry point behind handleS, so that the reified wrapper inlines nothing
// but a KClass lookup.
@PublishedApi
internal fun <E : Effect<*>, S, A> Program<A>.handleSWith(
    type: KClass<E>,
    initialState: S,
    rule: suspend ProgramScope.(S, E) -> Pair<S, Any?>,
): Program<A> =
    interpretSWith(type, initialState) { s, op, resume ->
        runRuleBlock({ rule(s, op) }) { (newState, response) -> resume(newState, checkResponse(op, response)) }
    }
