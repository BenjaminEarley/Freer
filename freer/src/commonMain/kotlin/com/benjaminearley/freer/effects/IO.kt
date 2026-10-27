@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.force
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.resume
import com.benjaminearley.freer.trampolineMisuse

/**
 * Real (possibly asynchronous) I/O, emitted with [performIO] — typically by handlers
 * that translate their effects into I/O — and run at the edge by [io] or, on the JVM,
 * `ioBlocking()`.
 */
public sealed interface IO<out R> : Effect<R> {
    /** Run [thunk] and respond with its result. */
    public data class Suspend<R>(
        public val thunk: suspend () -> R,
    ) : IO<R>
}

/** A program that runs [block] when interpreted by [io] and produces its result. */
public fun <R> performIO(block: suspend () -> R): Program<R> = perform(IO.Suspend(block))

/**
 * Terminal suspend runner: runs every IO thunk and returns the program's final value.
 * Apply it after all other handlers; any other effect left fails. Under a coroutine
 * dispatcher, interpretation stays on that dispatcher; in an interceptor-free context
 * the first thunk that resumes on another thread moves the rest of interpretation
 * there (on the JVM, use `ioBlocking()` instead).
 */
public suspend fun <A> Program<A>.io(): A {
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
                if (effect is IO.Suspend<*>) {
                    val result = (effect as IO.Suspend<Any?>).thunk()
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
