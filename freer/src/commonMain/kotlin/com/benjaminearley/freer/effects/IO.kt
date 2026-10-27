@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.force
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.resume
import com.benjaminearley.freer.trampolineMisuse

// Handlers that need real async I/O emit IO effects via performIO { }.
// A single suspend io() at the edge handles them all.
sealed interface IO<out R> : Effect<R>

data class SuspendIO<R>(
    val thunk: suspend () -> R,
) : IO<R>

fun <R> performIO(block: suspend () -> R): Program<R> = perform(SuspendIO(block))

// Terminal suspend runner. Apply AFTER all other effect handlers have stripped
// their effects: runs every IO thunk and returns the program's final value.
// Threading: thunk resumptions re-enter through the ContinuationInterceptor of the
// calling context, if any — under a coroutine dispatcher, interpretation stays on
// that dispatcher. In interceptor-free contexts (e.g. a bare suspend main), the
// first thunk that resumes on a foreign thread migrates the rest of interpretation
// onto it; use ioBlocking() there instead.
suspend fun <A> Program<A>.io(): A {
    var current: Program<A> = this
    while (true) {
        when (val c = current) {
            is Program.Done -> {
                return c.value
            }

            is Program.Defer -> current = c.force()

            is Program.Bounce -> trampolineMisuse()

            is Program.Suspended<*, *> -> {
                val suspended = c as Program.Suspended<Any?, A>
                val effect = suspended.effect
                if (effect is SuspendIO<*>) {
                    val result = (effect as SuspendIO<Any?>).thunk()
                    current = resume(suspended.pipeline, result)
                } else {
                    error(
                        "io() encountered unhandled effect: ${effect::class.simpleName}. " +
                            "Apply io() after all other effect handlers.",
                    )
                }
            }
        }
    }
}
