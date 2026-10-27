/*
 * Generators: reifying the continuation as data.
 *
 * The coroutine() handler turns a program that performs Yield effects into a
 * Status value: either Done with the result, or Yielded holding the value and
 * the rest of the program as a callable continuation. The driver decides when
 * (and whether) to resume — the suspended program is just data in between.
 */
package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform

public sealed interface Emit<out R> : Effect<R>

public data class Yield(
    val value: Int,
) : Emit<Unit>

public fun yieldValue(value: Int): Program<Unit> = perform(Yield(value))

public sealed class Status<out A> {
    public data class Finished<out A>(
        val result: A,
    ) : Status<A>()

    public class Yielded<out A>(
        public val value: Int,
        public val resume: () -> Program<Status<A>>,
    ) : Status<A>()
}

// The continuation is captured and invoked after the rule has returned — a
// deferred resume, so it produces a real Program each time it's called.
public fun <A> Program<A>.coroutine(): Program<Status<A>> =
    interpret<Emit<*>, A, Status<A>>(
        transformDone = { a -> Program.Done(Status.Finished(a)) },
        rule = { op, resume ->
            when (op) {
                is Yield -> Program.Done(Status.Yielded(op.value) { resume(Unit) })
            }
        },
    )
