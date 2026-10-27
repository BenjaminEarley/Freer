package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform

/**
 * Handle [Failure] as a [Result]: success with the final value, or failure carrying the
 * [Failure.Raise] reason, in which case the rest of the program is abandoned.
 */
public fun <A> Program<A>.attempt(): Program<Result<A>> =
    interpret<Failure<*>, A, Result<A>>(
        transformDone = { value -> Program.Done(Result.success(value)) },
        rule = { op, _ ->
            when (op) {
                // Short-circuit: We ignore the 'resume' function
                is Failure.Raise -> Program.Done(Result.failure(Exception(op.reason)))
            }
        },
    )

/**
 * Recover from a failure: on [Failure.Raise], the rest of the failed program is abandoned
 * (there is nothing to resume into) and [fallback] replaces it. A failure raised
 * *inside* [fallback] is not caught here — it propagates to the next outer
 * [recover] or [attempt].
 *
 * Any effect [fallback] performs is only seen by handlers applied *after* this
 * recover — handlers already applied beneath it never see the fallback program.
 * A fallback that logs, for example, needs the log handler outside the recover.
 */
public fun <A> Program<A>.recover(fallback: (String) -> Program<A>): Program<A> =
    interpret<Failure<*>, A> { op, _ ->
        when (op) {
            is Failure.Raise -> fallback(op.reason)
        }
    }

/** Failure: handled by [attempt] or [recover]. */
public sealed interface Failure<out R> : Effect<R> {
    /** Fail with [reason]; never answered, so the program does not continue past it. */
    public class Raise internal constructor(
        public val reason: String,
    ) : Failure<Nothing> {
        override fun toString(): String = "Raise($reason)"
    }
}

/** Fail with [reason]: performs [Failure.Raise]. */
public fun raise(reason: String): Program<Nothing> = perform(Failure.Raise(reason))
