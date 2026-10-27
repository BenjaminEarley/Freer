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
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.perform

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
    handle<Reader<*>, A> { op ->
        when (op) {
            is Reader.Ask -> env
        }
    }

/** Run this sub-program with the environment transformed; the override ends with it. */
public fun <E, A> Program<A>.local(transform: (E) -> E): Program<A> =
    ask<E>().flatMap { e ->
        val env = transform(e)
        this@local.intercept<Reader<*>, A> { op, _ ->
            when (op) {
                is Reader.Ask -> env
            }
        }
    }
