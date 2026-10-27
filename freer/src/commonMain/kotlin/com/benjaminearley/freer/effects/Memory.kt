package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handleS
import com.benjaminearley.freer.interpretS
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform

public fun <A> Program<A>.memory(initialState: Int): Program<A> =
    handleS<Memory<*>, Int, A>(initialState) { s, op ->
        when (op) {
            is Recall -> s to s // state unchanged, return the state
            is Memorize -> op.value to Unit // new state, return Unit
        }
    }

/** Like [memory], but also returns the final state alongside the result. */
public fun <A> Program<A>.memoryWithState(initialState: Int): Program<Pair<A, Int>> =
    interpretS<Memory<*>, Int, A, Pair<A, Int>>(
        initialState = initialState,
        transformDone = { s, a -> Program.Done(a to s) },
        rule = { s, op, resume ->
            when (op) {
                is Recall -> resume(s, s)
                is Memorize -> resume(op.value, Unit)
            }
        },
    )

/**
 * Run this program as a transaction: its Memory effects hit a local copy of the
 * state, seeded by reading the downstream handler, and the final local state is
 * committed downstream only if the program completes. If it fails part-way
 * (e.g. a [com.benjaminearley.freer.effects.Raise] handled outside), the commit is part of the
 * abandoned continuation and never runs — the downstream state stays untouched.
 * Built from flatMap alone, so it is safe under a multi-shot handler such as
 * collectAll: each branch reads, runs and commits independently.
 */
public fun <A> Program<A>.transactional(): Program<A> =
    recall().flatMap { snapshot ->
        this@transactional.memoryWithState(snapshot).flatMap { (result, finalState) ->
            memorize(finalState).map { result }
        }
    }

public sealed interface Memory<out R> : Effect<R>

public data class Memorize(
    val value: Int,
) : Memory<Unit> // Put

public data object Recall : Memory<Int> // Get

public fun memorize(value: Int): Program<Unit> = perform(Memorize(value))

public fun recall(): Program<Int> = perform(Recall)
