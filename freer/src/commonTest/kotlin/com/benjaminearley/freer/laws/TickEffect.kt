/*
 * A counting effect for the law tests, handled in every style the library offers and by the
 * model, so that the styles can be compared with each other and with the model.
 */
package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.handleS
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.interpretS
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.reference.Ref
import com.benjaminearley.freer.reference.flatMap
import com.benjaminearley.freer.reference.handleRelay
import com.benjaminearley.freer.reference.handleRelayS
import com.benjaminearley.freer.reference.interpose
import com.benjaminearley.freer.reference.pure
import com.benjaminearley.freer.reference.send

/** Count a step and learn how many came before it. */
data object Tick : Effect<Int>

fun tick(): Program<Int> = perform(Tick)

/** Answers each tick with the count so far and reports the total next to the final value. */
fun <A> Program<A>.countTicks(start: Int = 0): Program<Pair<A, Int>> =
    interpretS<Tick, Int, A, Pair<A, Int>>(start, { count, value -> Program.Done(value to count) }) { count, _, resume ->
        resume(count + 1, count)
    }

/**
 * countTicks written with the stateless interpret: the count travels in the result, which is
 * a function of the count to start from. Each tick's continuation is resumed inside that
 * function, after the rule has returned, so every resume is deferred and the functions are
 * pure and replayable.
 */
fun <A> Program<A>.countTicksByFunctions(start: Int = 0): Program<Pair<A, Int>> =
    interpret<Tick, A, (Int) -> Program<Pair<A, Int>>>(
        transformDone = { value -> Program.Done { count -> Program.Done(value to count) } },
        rule = { _, resume -> Program.Done { count -> resume(count).flatMap { continueWith -> continueWith(count + 1) } } },
    ).flatMap { continueWith -> continueWith(start) }

/** Answers each tick with the count so far through the stateful block API, and drops the total. */
fun <A> Program<A>.countTicksQuietly(start: Int = 0): Program<A> = handleS<Tick, Int, A>(start) { count, _ -> (count + 1) to count }

/** Answers every tick with the same number, through the block API. */
fun <A> Program<A>.constantTicks(answer: Int): Program<A> = handle<Tick, A> { answer }

/** As constantTicks, through the low-level API with a direct resume. */
fun <A> Program<A>.constantTicksDirect(answer: Int): Program<A> = interpret<Tick, A> { _, resume -> resume(answer) }

/** Middleware that adds [delta] to the answer of every tick. */
fun <A> Program<A>.shiftTicks(delta: Int): Program<A> = intercept<Tick, A> { _, proceed -> (proceed() as Int) + delta }

// The same handlers on the model.

fun <A> Ref<A>.countTicks(start: Int = 0): Ref<Pair<A, Int>> =
    handleRelayS<Tick, Int, A, Pair<A, Int>>(start, { count, value -> pure(value to count) }) { count, _, resume ->
        resume(count + 1, count)
    }

fun <A> Ref<A>.constantTicks(answer: Int): Ref<A> = handleRelay<Tick, A, A>({ pure(it) }) { _, resume -> resume(answer) }

fun <A> Ref<A>.shiftTicks(delta: Int): Ref<A> =
    interpose<Tick, A, A>({ pure(it) }) { op, resume -> send(op).flatMap { answer -> resume(answer + delta) } }
