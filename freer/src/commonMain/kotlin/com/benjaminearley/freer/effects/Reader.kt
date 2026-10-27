/*
 * A Reader effect: ask for a value from the environment.
 *
 * local() overrides the environment for one sub-program without consuming the
 * effect: it asks downstream once for the current value, then an intercept answers
 * Ask with the transformed value instead of proceeding downstream, so everything
 * outside the override still gets the downstream answer. Both steps are plain
 * flatMap and a rule that never suspends, so local() also works under a
 * multi-shot handler such as collectAll.
 */
package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.perform

public sealed interface Reader<out R> : Effect<R>

public data object AskEnv : Reader<String>

public fun ask(): Program<String> = perform(AskEnv)

public fun <A> Program<A>.runReader(env: String): Program<A> =
    handle<Reader<*>, A> { op ->
        when (op) {
            is AskEnv -> env
        }
    }

/** Run this sub-program with the environment transformed; the override ends with it. */
public fun <A> Program<A>.local(transform: (String) -> String): Program<A> =
    ask().flatMap { e ->
        val env = transform(e)
        this@local.intercept<Reader<*>, A> { op, _ ->
            when (op) {
                is AskEnv -> env
            }
        }
    }
