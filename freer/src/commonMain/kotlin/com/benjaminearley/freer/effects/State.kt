@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.interpretS
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform

/**
 * Handle every [State] operation with a state seeded from [initial].
 *
 * The state type is erased: the handler stores whatever [State.Put] carries and
 * hands it back on [State.Get], and [getState] trusts its caller about the type.
 * A handler stack therefore carries one state type — this handler consumes every
 * State operation above it, whatever type it was meant for, so an outer runState
 * with a different type never sees them.
 */
public fun <S, A> Program<A>.runState(initial: S): Program<A> =
    interpretS<State<*>, Any?, A>(initial) { s, op, resume ->
        when (op) {
            is State.Get -> resume(s, s) // state unchanged, return the state
            is State.Put -> resume(op.value, Unit) // new state, return Unit
        }
    }

/** Like [runState], but also returns the final state alongside the result. */
public fun <S, A> Program<A>.runStateWithFinal(initial: S): Program<Pair<A, S>> =
    interpretS<State<*>, Any?, A, Pair<A, S>>(
        initialState = initial,
        transformDone = { s, a -> Program.Done(a to s as S) },
        rule = { s, op, resume ->
            when (op) {
                is State.Get -> resume(s, s)
                is State.Put -> resume(op.value, Unit)
            }
        },
    )

/**
 * Run this program as a transaction: its State effects hit a local copy of the
 * state, seeded by reading the downstream handler, and the final local state is
 * committed downstream only if the program completes. If it fails part-way
 * (e.g. a [com.benjaminearley.freer.effects.Error.Raise] handled outside), the commit is part of the
 * abandoned continuation and never runs — the downstream state stays untouched.
 * Built from flatMap alone, so it is safe under a multi-shot handler such as
 * collectAll: each branch reads, runs and commits independently.
 *
 * The state passes through untouched, so no state type is needed here.
 */
public fun <A> Program<A>.transactional(): Program<A> =
    getState<Any?>().flatMap { snapshot ->
        this@transactional.runStateWithFinal(snapshot).flatMap { (result, finalState) ->
            putState(finalState).map { result }
        }
    }

/** A single mutable cell, read with [getState] and written with [putState]. */
public sealed interface State<out R> : Effect<R> {
    /** Read the current state. */
    public data object Get : State<Any?>

    /** Replace the state with [value]; responds with Unit. */
    public data class Put(
        public val value: Any?,
    ) : State<Unit>
}

/** Read the current state. Unchecked: [S] must be the type the handler holds. */
public fun <S> getState(): Program<S> = perform(State.Get) as Program<S>

/** Replace the current state with [value]. */
public fun <S> putState(value: S): Program<Unit> = perform(State.Put(value))

/** Replace the current state with [f] applied to it: a [getState] followed by a [putState]. */
public fun <S> modifyState(f: (S) -> S): Program<Unit> = getState<S>().flatMap { s -> putState(f(s)) }
