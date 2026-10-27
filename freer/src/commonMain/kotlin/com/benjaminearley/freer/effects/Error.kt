package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform

public fun <A> Program<A>.raise(): Program<Result<A>> =
    interpret<Error<*>, A, Result<A>>(
        transformDone = { value -> Program.Done(Result.success(value)) },
        rule = { op, _ ->
            when (op) {
                // Short-circuit: We ignore the 'resume' function
                is Error.Raise -> Program.Done(Result.failure(Exception(op.reason)))
            }
        },
    )

/**
 * Recover from a failure: on [Error.Raise], the rest of the failed program is abandoned
 * (there is nothing to resume into) and [recover] replaces it. A failure raised
 * *inside* [recover] is not caught here — it propagates to the next outer
 * [catchError] or [raise].
 *
 * Any effect [recover] performs is only seen by handlers applied *after* this
 * catchError — handlers already applied beneath it never see the recovery program.
 * A recovery that logs, for example, needs the log handler outside the catch.
 */
public fun <A> Program<A>.catchError(recover: (String) -> Program<A>): Program<A> =
    interpret<Error<*>, A> { op, _ ->
        when (op) {
            is Error.Raise -> recover(op.reason)
        }
    }

public sealed interface Error<out R> : Effect<R> {
    public data class Raise(
        public val reason: String,
    ) : Error<Nothing>
}

public fun fail(reason: String): Program<Nothing> = perform(Error.Raise(reason))
