package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Left
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.Right
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.effects.recover
import com.benjaminearley.freer.effects.runReader
import com.benjaminearley.freer.effects.runStateWithFinal
import com.benjaminearley.freer.effects.transactional
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.runOrThrow
import com.benjaminearley.freer.toEither
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Handler order decides what a failure does to state: a failure handler inside the state
 * handler keeps the writes made before the failure, outside it drops the whole result, and a
 * transaction drops the writes while keeping the state handler outside.
 */
class HandlerOrderingTest {
    private val arbFailureFree = arbExpr(Alphabet(choose = false, raise = false))
    private val arbReason: Arb<String> = Arb.element("boom", "bust")

    // A program with its ticks and asks already answered, so that only state and failure remain.
    private fun Expr.prepared(env: Int): Program<Int> = toProgram().runReader(env).constantTicks(7)

    private fun Expr.stateAfter(
        env: Int,
        initial: Int,
    ): Int = prepared(env).runStateWithFinal(initial).runOrThrow().second

    @Test
    fun aFailureHandlerInsideTheStateHandlerKeepsTheWrites() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbFailureFree, arbPair(arbSmallInt, arbSmallInt), arbReason) { q, (env, initial), reason ->
                val failing = q.prepared(env).flatMap { raise(reason) }
                assertEquals(Left(reason) to q.stateAfter(env, initial), failing.attempt().toEither().runStateWithFinal(initial).runOrThrow())
                assertEquals(-1 to q.stateAfter(env, initial), failing.recover { Program.Done(-1) }.runStateWithFinal(initial).runOrThrow())
            }
        }

    @Test
    fun aFailureHandlerOutsideTheStateHandlerDropsTheWrites() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbFailureFree, arbPair(arbSmallInt, arbSmallInt), arbReason) { q, (env, initial), reason ->
                val failing = q.prepared(env).flatMap { raise(reason) }
                assertEquals(Left(reason), failing.runStateWithFinal(initial).attempt().toEither().runOrThrow())
            }
        }

    @Test
    fun aTransactionDropsTheWritesOfAFailedProgram() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbFailureFree, arbPair(arbSmallInt, arbSmallInt), arbReason) { q, (env, initial), reason ->
                val failing = q.prepared(env).flatMap { raise(reason) }
                assertEquals(Left(reason) to initial, failing.transactional().attempt().toEither().runStateWithFinal(initial).runOrThrow())
            }
        }

    @Test
    fun aTransactionIsInvisibleWhenTheProgramFinishes() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbFailureFree, arbPair(arbSmallInt, arbSmallInt)) { q, (env, initial) ->
                assertEquals(q.prepared(env).runStateWithFinal(initial).runOrThrow(), q.prepared(env).transactional().runStateWithFinal(initial).runOrThrow())
            }
        }

    @Test
    fun withoutAFailureTheOrderDoesNotMatter() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbFailureFree, arbPair(arbSmallInt, arbSmallInt)) { q, (env, initial) ->
                val (value, final) = q.prepared(env).runStateWithFinal(initial).runOrThrow()
                assertEquals(Right(value) to final, q.prepared(env).attempt().toEither().runStateWithFinal(initial).runOrThrow())
                assertEquals(Right(value to final), q.prepared(env).runStateWithFinal(initial).attempt().toEither().runOrThrow())
            }
        }
}
