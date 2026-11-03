package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.getState
import com.benjaminearley.freer.effects.putState
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.map
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The four equations of a single mutable cell. Each law is checked in context: after a
 * random prefix that may have used every effect, and before a random continuation of
 * the law's value, under a random complete stack.
 */
class StateLawsTest {
    private val arbPrefix = arbExpr()
    private val arbContinuation = arbExpr(scope = 1)
    private val arbContinuationOfTwo = arbExpr(scope = 2)

    private fun Expr.after(
        prefix: Expr,
        law: Program<Int>,
    ): Program<Int> = prefix.toProgram().flatMap { law }.flatMap { value -> toProgram(listOf(value)) }

    @Test
    fun getThenGetReadsTheSameValueTwice() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPrefix, arbContinuationOfTwo, arbStack()) { prefix, k, stack ->
                val twice = getState<Int>().flatMap { s -> getState<Int>().flatMap { s2 -> k.toProgram(listOf(s2, s)) } }
                val once = getState<Int>().flatMap { s -> k.toProgram(listOf(s, s)) }
                assertSameUnder(stack, prefix.toProgram().flatMap { twice }, prefix.toProgram().flatMap { once })
            }
        }

    @Test
    fun getThenPutChangesNothing() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPrefix, arbContinuation, arbStack()) { prefix, k, stack ->
                val writeBack = getState<Int>().flatMap { s -> putState(s) }.map { 0 }
                assertSameUnder(stack, k.after(prefix, writeBack), k.after(prefix, Program.Done(0)))
            }
        }

    @Test
    fun putThenGetReadsWhatWasPut() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbPrefix, arbSmallInt), arbContinuation, arbStack()) { (prefix, s), k, stack ->
                assertSameUnder(stack, k.after(prefix, putState(s).flatMap { getState<Int>() }), k.after(prefix, putState(s).map { s }))
            }
        }

    @Test
    fun putThenPutIsTheSecondPut() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTriple(arbPrefix, arbSmallInt, arbSmallInt), arbContinuation, arbStack()) { (prefix, s, s2), k, stack ->
                assertSameUnder(stack, k.after(prefix, putState(s).flatMap { putState(s2) }.map { 0 }), k.after(prefix, putState(s2).map { 0 }))
            }
        }
}
