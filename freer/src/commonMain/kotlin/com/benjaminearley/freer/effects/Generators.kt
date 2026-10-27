/*
 * Generators: reifying the continuation as data.
 *
 * The coroutine() handler turns a program that performs Yield effects into a
 * Status value: either Finished with the result, or Yielded holding the value and
 * the rest of the program as a callable continuation. The driver decides when
 * (and whether) to resume — the suspended program is just data in between.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform

public sealed interface Emit<out R> : Effect<R> {
    /** Hand [value] to the driver and pause until it resumes the generator. */
    public data class Yield<out T>(
        public val value: T,
    ) : Emit<Unit>
}

public fun <T> yieldValue(value: T): Program<Unit> = perform(Emit.Yield(value))

public sealed class Status<out T, out A> {
    public data class Finished<out A>(
        public val result: A,
    ) : Status<Nothing, A>()

    public class Yielded<out T, out A>(
        public val value: T,
        public val resume: () -> Program<Status<T, A>>,
    ) : Status<T, A>()
}

// The continuation is captured and invoked after the rule has returned — a
// deferred resume, so it produces a real Program each time it's called.
// The yielded type is erased like any effect payload: every Yield this handler
// sees is reported as a T, so one coroutine() should see one yielded type.
public fun <T, A> Program<A>.coroutine(): Program<Status<T, A>> =
    interpret<Emit<*>, A, Status<T, A>>(
        transformDone = { a -> Program.Done(Status.Finished(a)) },
        rule = { op, resume ->
            when (op) {
                is Emit.Yield<*> -> Program.Done(Status.Yielded(op.value as T) { resume(Unit) })
            }
        },
    )
