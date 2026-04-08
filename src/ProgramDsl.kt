@file:Suppress("UNCHECKED_CAST")

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

@RestrictsSuspension
class ProgramScope : Continuation<Any?> {
    override val context: CoroutineContext = EmptyCoroutineContext

    private sealed class State {
        class Suspended(
            val program: Program<Any?>,
            val continuation: Continuation<Any?>,
        ) : State()

        class Completed(
            val value: Any?,
        ) : State()
    }

    private var state: State? = null

    override fun resumeWith(result: Result<Any?>) {
        state = State.Completed(result.getOrThrow())
    }

    suspend fun <A> Program<A>.bind(): A =
        suspendCoroutineUninterceptedOrReturn { cont ->
            state = State.Suspended(this@bind as Program<Any?>, cont as Continuation<Any?>)
            COROUTINE_SUSPENDED
        }

    internal fun <A> buildProgram(): Program<A> =
        when (val s = state!!) {
            is State.Completed -> {
                Program.Done(s.value as A)
            }

            is State.Suspended -> {
                s.program.flatMap { value ->
                    s.continuation.resumeWith(Result.success(value))
                    buildProgram()
                }
            }
        }
}

fun <A> program(block: suspend ProgramScope.() -> A): Program<A> {
    val scope = ProgramScope()
    block.createCoroutineUnintercepted(receiver = scope, completion = scope).resume(Unit)
    return scope.buildProgram()
}

// handle: interpret with auto-resume. The block's return value is the effect response.
inline fun <reified E : Effect<*>, A> Program<A>.handle(noinline rule: suspend ProgramScope.(E) -> Any?): Program<A> =
    interpret<E, A> { op, resume ->
        when (val result = program { rule(op) }) {
            is Program.Done -> resume(result.value)
            is Program.Suspended<*, *> -> result.flatMap { response -> resume(response) }
        }
    }

// intercept: middleware pattern. Observes an effect, calls proceed() to re-emit
// it to a downstream handler, and can act on the result.
// proceed() performs the effect — call it where you want the effect to execute.
// This is called interpose from the paper.
inline fun <reified E : Effect<*>, A> Program<A>.intercept(
    noinline rule: suspend ProgramScope.(effect: E, proceed: suspend ProgramScope.() -> Any?) -> Any?,
): Program<A> =
    interpret<E, A> { op, resume ->
        val proceed: suspend ProgramScope.() -> Any? = { perform(op).bind() }
        when (val result = program { rule(op, proceed) }) {
            is Program.Done -> resume(result.value)
            is Program.Suspended<*, *> -> result.flatMap { response -> resume(response) }
        }
    }

// handleS: stateful interpret with auto-resume. Returns Pair(newState, response).
inline fun <reified E : Effect<*>, S, A> Program<A>.handleS(
    initialState: S,
    noinline rule: suspend ProgramScope.(S, E) -> Pair<S, Any?>,
): Program<A> =
    interpretS<E, S, A>(initialState) { s, op, resume ->
        when (val result = program { rule(s, op) }) {
            is Program.Done -> {
                val (newState, response) = result.value
                resume(newState, response)
            }

            is Program.Suspended<*, *> -> {
                result.flatMap { (newState, response) -> resume(newState, response) }
            }
        }
    }
