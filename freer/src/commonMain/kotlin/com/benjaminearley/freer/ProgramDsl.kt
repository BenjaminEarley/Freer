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

/**
 * Receiver scope of [program] blocks: sequence sub-programs with [bind]. Suspension is
 * restricted, so only Program binds — not arbitrary suspend calls — can occur inside.
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
@PublishedApi
internal class ProgramScopeImpl @PublishedApi internal constructor() :
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

    @PublishedApi
    internal fun <A> buildProgram(): Program<A> {
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
                // Suspended or Defer: queue the continuation behind it without
                // forcing. Invariant: a Defer is never forced from inside a step
                // or a bind — only force() and the interpreter loops force, and
                // they loop. program{} runs buildProgram inside its own Defer's
                // thunk, so forcing here would nest one JVM frame per level
                // whenever the bound Defer's thunk binds another Defer (nested
                // recursive program{} blocks, or a rule that binds resume).
                // then(step) appends to the Defer's pending queue in O(1), and
                // the enclosing force() loop drains the chain iteratively.
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
 * Build a [Program] in direct style: `.bind()` each sub-program instead of chaining
 * flatMap. The block is captured, not run — each interpretation forces it afresh, so
 * program{} values are pure, replayable descriptions that can be interpreted any number
 * of times, under different handler stacks.
 */
public fun <A> program(block: suspend ProgramScope.() -> A): Program<A> =
    Program.Defer {
        val scope = ProgramScopeImpl()
        block.createCoroutineUnintercepted(receiver = scope, completion = scope).resume(Unit)
        scope.buildProgram()
    }

// Run a rule block eagerly and continue with [onValue] on its result. A block that
// returns without suspending hands its value over directly, with no completion round
// trip and no Program wrapper; one that suspends is built into a concrete
// Done/Suspended program — program(block).force() without the intermediate Defer —
// and [onValue] is queued behind it. Handler rules force immediately, so nothing
// needs the laziness; program{} keeps it for users. The per-effect coroutine is
// scalar-replaced by the JIT when the rule returns without suspending — its measured
// cost is zero in that case, so there is deliberately no non-suspend fast-path
// overload.
@PublishedApi
internal inline fun <A, B> runRuleBlock(
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
@PublishedApi
internal fun checkResponse(
    effect: Any,
    response: Any?,
): Any? {
    if (response is Program<*>) programResponseError(effect)
    return response
}

internal fun programResponseError(effect: Any): Nothing {
    val name = effect::class.simpleName ?: effect.toString()
    throw IllegalStateException(
        "Rule for $name returned a Program instead of a response. Missing .bind()? " +
            "handle, intercept and handleS responses are plain values; use interpret to return a program.",
    )
}

/** Interpret with auto-resume: the block's return value is the effect's response. */
public inline fun <reified E : Effect<*>, A> Program<A>.handle(noinline rule: suspend ProgramScope.(E) -> Any?): Program<A> =
    interpret<E, A> { op, resume ->
        runRuleBlock({ rule(op) }) { response -> resume(checkResponse(op, response)) }
    }

// The proceed() continuation handed to an intercept rule: re-emits the intercepted
// effect and yields the downstream response, exactly as `perform(effect).bind()`
// would. A class rather than a suspend lambda capturing the effect: the object holds
// only the effect reference, and suspending through the intrinsic directly needs no
// state machine, whereas a suspend lambda is copied together with its continuation
// fields on every invocation. It cannot call bind() itself — a suspend function
// outside the restricted scope may not — so it parks the program via suspendOn.
@PublishedApi
internal class Proceed(
    private val effect: Effect<*>,
) : suspend (ProgramScope) -> Any? {
    override suspend fun invoke(scope: ProgramScope): Any? =
        suspendCoroutineUninterceptedOrReturn { cont -> scope.suspendOn(perform(effect), cont) }
}

/**
 * Middleware (the paper's interpose): observe an effect without consuming it. proceed()
 * re-emits the effect to a downstream handler and returns its response — call it where
 * the effect should execute, and rewrite (or skip) the result as needed.
 */
public inline fun <reified E : Effect<*>, A> Program<A>.intercept(
    noinline rule: suspend ProgramScope.(effect: E, proceed: suspend ProgramScope.() -> Any?) -> Any?,
): Program<A> =
    interpret<E, A> { op, resume ->
        val proceed = Proceed(op)
        runRuleBlock({ rule(op, proceed) }) { response -> resume(checkResponse(op, response)) }
    }

/**
 * Stateful [handle]: the block receives the current state and returns `newState to response`.
 * Mind the pair order when the state and the response share a type — `response to newState`
 * is equally well-typed and silently swaps the two.
 */
public inline fun <reified E : Effect<*>, S, A> Program<A>.handleS(
    initialState: S,
    noinline rule: suspend ProgramScope.(S, E) -> Pair<S, Any?>,
): Program<A> =
    interpretS<E, S, A>(initialState) { s, op, resume ->
        runRuleBlock({ rule(s, op) }) { (newState, response) -> resume(newState, checkResponse(op, response)) }
    }
