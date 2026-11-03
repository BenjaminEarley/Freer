package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.ask
import com.benjaminearley.freer.effects.local
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.map
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** The equations of a read-only environment, and of rebinding it for a sub-program with local. */
class ReaderLawsTest {
    private val arbContinuation = arbExpr(scope = 1)
    private val arbContinuationOfTwo = arbExpr(scope = 2)

    private fun shiftBy(delta: Int): (Int) -> Int = { it + delta }

    @Test
    fun askThenAskReadsTheSameValueTwice() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbContinuationOfTwo, arbStack()) { k, stack ->
                val twice = ask<Int>().flatMap { e -> ask<Int>().flatMap { e2 -> k.toProgram(listOf(e2, e)) } }
                val once = ask<Int>().flatMap { e -> k.toProgram(listOf(e, e)) }
                assertSameUnder(stack, twice, once)
            }
        }

    @Test
    fun anUnusedAskChangesNothing() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbStack()) { expr, stack ->
                val m = expr.toProgram()
                assertSameUnder(stack, ask<Int>().flatMap { m }, m)
            }
        }

    @Test
    fun localAppliesTheTransformToAsk() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbSmallInt, arbStack()) { delta, stack ->
                assertSameUnder(stack, ask<Int>().local(shiftBy(delta)), ask<Int>().map(shiftBy(delta)))
            }
        }

    @Test
    fun localOfAValueIsTheValue() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbSmallInt, arbSmallInt), arbStack()) { (a, delta), stack ->
                assertSameUnder(stack, Program.Done(a).local<Int, Int>(shiftBy(delta)), Program.Done(a))
            }
        }

    @Test
    fun localDistributesOverBind() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbExpr(), arbSmallInt), arbContinuation, arbStack()) { (expr, delta), k, stack ->
                val m = expr.toProgram()
                val f = shiftBy(delta)
                val continueWith = { value: Int -> k.toProgram(listOf(value)) }
                assertSameUnder(stack, m.flatMap(continueWith).local(f), m.local(f).flatMap { continueWith(it).local(f) })
            }
        }

    @Test
    fun nestedLocalsCompose() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTriple(arbExpr(), arbSmallInt, arbSmallInt), arbStack()) { (expr, delta, scale), stack ->
                val m = expr.toProgram()
                val outer = shiftBy(delta)
                val inner = { e: Int -> e * scale + 1 }
                assertSameUnder(stack, m.local(inner).local(outer), m.local { e: Int -> inner(outer(e)) })
            }
        }

    @Test
    fun localOfTheIdentityChangesNothing() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbStack()) { expr, stack ->
                val m = expr.toProgram()
                assertSameUnder(stack, m.local { e: Int -> e }, m)
            }
        }
}
