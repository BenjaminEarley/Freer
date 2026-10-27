/*
 * Nondeterminism: multi-shot continuations.
 *
 * choose(options) suspends on a Choose effect; the collectAll handler resumes
 * the SAME captured continuation once per option and concatenates the results.
 * Resuming a continuation more than once is impossible with one-shot suspend
 * coroutines — it works here because the continuation is an immutable Pipeline
 * value that can be replayed.
 *
 * program { } blocks and the rule blocks of handle, intercept and handleS are
 * one-shot coroutines, so one fails under these handlers only when its OWN
 * continuation is resumed more than once: the block binds the choice itself,
 * proceeds on it, or binds a sub-program that performs it. A program { } created
 * inside a flatMap closure after the choice is fine (each branch gets a fresh
 * one), as is a rule that answers without suspending, or middleware on an
 * unrelated effect.
 *
 * Three consequences worth knowing: d sequential choices of s options explore
 * s^d branches — there is no implicit pruning. A stateful handler applied inside
 * collectAll backtracks its state per branch; applied outside, one global state
 * threads across all branches. And catchError inside collectAll recovers a failed
 * branch in isolation; outside, one failed branch replaces the entire collection.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.program

sealed interface NonDet<out R> : Effect<R>

data class Choose(
    val options: List<Any?>,
) : NonDet<Any?>

fun <T> choose(options: List<T>): Program<T> = perform(Choose(options)) as Program<T>

/** Prune this branch unless [condition] holds. */
fun guard(condition: Boolean): Program<Unit> = if (condition) Program.DONE_UNIT else choose(emptyList())

/** Run every branch to completion and collect all results in order. */
fun <A> Program<A>.collectAll(): Program<List<A>> =
    interpret<NonDet<*>, A, List<A>>(
        transformDone = { a -> Program.Done(listOf(a)) },
        rule = { op, resume ->
            when (op) {
                is Choose ->
                    // The program{} block defers the resume calls out of the rule body
                    // (a direct resume is single-shot); by the time the block runs, each
                    // resume(option) replays the same captured continuation — multi-shot.
                    // The block itself is interpreted once per Choose occurrence, so its
                    // own single-shot constraint is not violated.
                    program {
                        val all = mutableListOf<A>()
                        for (option in op.options) {
                            all += resume(option).bind()
                        }
                        all
                    }
            }
        },
    )

/** Binary choice between two programs. */
fun <A> orElse(
    first: Program<A>,
    second: Program<A>,
): Program<A> = choose(listOf(true, false)).flatMap { pickFirst -> if (pickFirst) first else second }

/**
 * Split the search: null if it has no answers, otherwise the first answer paired
 * with the rest of the search as a replayable program. The rest is built in O(1):
 * the first answer's own rest, then one choice over the options not yet tried —
 * each of those is resumed only if the rest is run and reaches it. Splitting a
 * rest again nests one more msplit interpreter around the search, so enumerating
 * by repeated splits costs stack and time proportional to the number of prior
 * splits — a bound set by handler nesting, not by the number of options.
 */
fun <A> Program<A>.msplit(): Program<Pair<A, Program<A>>?> =
    interpret<NonDet<*>, A, Pair<A, Program<A>>?>(
        transformDone = { a -> Program.Done(a to choose(emptyList<A>())) },
        rule = { op, resume ->
            when (op) {
                is Choose ->
                    program {
                        var found: Pair<A, Program<A>>? = null
                        var next = 0
                        while (found == null && next < op.options.size) {
                            found = resume(op.options[next]).bind()
                            next++
                        }
                        val split = found
                        when {
                            split == null -> null
                            next == op.options.size -> split
                            else -> {
                                // One flat choice over the untried options, behind the first
                                // answer's own rest. The flatMap closure runs only when the
                                // rest is interpreted and reaches that option — after this
                                // rule has returned — so its resume call is deferred.
                                val remaining = op.options.subList(next, op.options.size)
                                val others = choose(remaining).flatMap { option -> unsplit(resume(option)) }
                                split.first to orElse(split.second, others)
                            }
                        }
                    }
            }
        },
    )

// Turn a split-shaped program back into an ordinary nondeterministic one.
private fun <A> unsplit(split: Program<Pair<A, Program<A>>?>): Program<A> =
    split.flatMap { s ->
        if (s == null) choose(emptyList()) else orElse(Program.Done(s.first), s.second)
    }

/** Committed choice: the first answer only; the rest of the search is never explored. */
fun <A> once(search: Program<A>): Program<A> =
    search.msplit().flatMap { s ->
        if (s == null) choose(emptyList()) else Program.Done(s.first)
    }

/**
 * Soft cut: if [condition] has any answer, run [then] over every one of its
 * answers; run [otherwise] only when it has none.
 */
fun <A, B> ifte(
    condition: Program<A>,
    then: (A) -> Program<B>,
    otherwise: Program<B>,
): Program<B> =
    condition.msplit().flatMap { s ->
        if (s == null) otherwise else orElse(then(s.first), s.second.flatMap(then))
    }
