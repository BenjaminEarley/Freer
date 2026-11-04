@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.force
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.resume

/**
 * Real, possibly asynchronous, I/O. A handler that needs it translates its effects into
 * [performIO] binds, and one `suspend` runner at the edge, [io] or on the JVM
 * `ioBlocking()`, runs them all:
 *
 * ```
 * fun <A> Program<A>.consoleAsync(): Program<A> =
 *     handle<Console<*>, A> { op ->
 *         when (op) {
 *             is Print -> performIO { sendToRemoteLog(op.line) }.bind()
 *             ReadLine -> performIO { fetchInputFromApi() }.bind()
 *         }
 *     }
 *
 * val name = greeter.consoleAsync().io()
 * ```
 */
public sealed interface IO<out R> : Effect<R> {
    /** Run [thunk] and respond with its result. */
    public class Suspend<R> internal constructor(
        public val thunk: suspend () -> R,
    ) : IO<R> {
        override fun toString(): String = "Suspend(...)"
    }
}

/** A program that runs [block] when interpreted by [io] and produces its result. */
public fun <R> performIO(block: suspend () -> R): Program<R> = perform(IO.Suspend(block))

/**
 * Terminal suspend runner, available on every target: runs every IO thunk and returns
 * the program's final value. Call it from a coroutine, after all other handlers, so that
 * only IO effects remain; any other effect left fails naming itself. Thunk resumptions
 * re-enter through the calling context's `ContinuationInterceptor`, so under a coroutine
 * dispatcher interpretation stays on that dispatcher; in an interceptor-free context (a
 * plain `suspend main`) the first thunk that resumes on another thread moves the rest of
 * interpretation there, which is what `ioBlocking()` avoids on the JVM.
 */
public suspend fun <A> Program<A>.io(): A {
    var current: Program<A> = this
    while (true) {
        when (val c = current) {
            is Program.Done -> {
                return c.value
            }

            is Program.Defer, is Program.Lazy -> current = c.force()

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
