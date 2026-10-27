@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.force
import com.benjaminearley.freer.resume
import com.benjaminearley.freer.trampolineMisuse
import java.util.concurrent.ArrayBlockingQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * JVM-only blocking edge runner for interceptor-free contexts (a plain main, a thread
 * you own): runs every IO thunk and returns the final value, blocking the caller while
 * a thunk is suspended. Every interpretation step stays on the calling thread, so
 * handler state needs no synchronization. Apply it after all other handlers.
 */
public fun <A> Program<A>.ioBlocking(): A {
    // Each thunk is started in place; if it suspends, the caller blocks until the
    // completion callback hands the Result across a queue — the transfer provides the
    // happens-before edge, and thunk failures are rethrown here, on the caller's
    // thread. The thunk itself may hop threads internally; only interpretation is
    // confined.
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
                    val thunk = (effect as IO.Suspend<Any?>).thunk
                    // Fresh box per thunk: a buggy thunk that resumes its continuation
                    // twice lands the second result in an orphaned box instead of
                    // poisoning the next thunk's handoff.
                    val box = ArrayBlockingQueue<Result<Any?>>(1)
                    val immediate =
                        thunk.startCoroutineUninterceptedOrReturn(
                            Continuation(EmptyCoroutineContext) { result -> box.put(result) },
                        )
                    val response = if (immediate === COROUTINE_SUSPENDED) box.take().getOrThrow() else immediate
                    current = resume(suspended.pipeline, response)
                } else {
                    error(
                        "ioBlocking() encountered unhandled effect: ${effect::class.simpleName}. " +
                            "Apply ioBlocking() after all other effect handlers.",
                    )
                }
            }
        }
    }
}
