@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform

/**
 * A read-only environment: [ask] reads it, [runReader] supplies it, [local] overrides it
 * for one sub-program.
 *
 * The environment type is erased: [Reader.Ask] is declared over `Any?`, so [ask] casts to
 * the type its caller names and no handler can check it, and a handler stack carries one
 * environment type (a second type needs a second effect class). Call sites often need the
 * type argument spelled out, as in `ask<String>()`.
 *
 * [local] asks downstream once for the current value, then answers every Ask inside the
 * sub-program itself with the transformed value, so those Asks never reach the downstream
 * handler; Asks outside the sub-program still get the downstream answer. Both steps are a
 * plain [flatMap] and a direct resume, so [local] also works under a multi-shot handler
 * such as [collectAll].
 */
public sealed interface Reader<out R> : Effect<R> {
    /** Read the environment. */
    public data object Ask : Reader<Any?>
}

/**
 * Read the environment. The environment type is erased, so this is unchecked:
 * [E] must be the type the nearest [runReader] (or [local]) supplies.
 */
public fun <E> ask(): Program<E> = perform(Reader.Ask) as Program<E>

/**
 * Answer every [Reader.Ask] with [env]. A handler stack carries one environment
 * type: this handler consumes every Ask above it, whatever type it was meant for.
 */
public fun <E, A> Program<A>.runReader(env: E): Program<A> =
    interpret<Reader<*>, A> { op, resume ->
        when (op) {
            is Reader.Ask -> resume(env)
        }
    }

/** Run this sub-program with the environment transformed; the override ends with it. */
public fun <E, A> Program<A>.local(transform: (E) -> E): Program<A> =
    ask<E>().flatMap { e ->
        val env = transform(e)
        this@local.interpret<Reader<*>, A> { op, resume ->
            when (op) {
                is Reader.Ask -> resume(env)
            }
        }
    }
