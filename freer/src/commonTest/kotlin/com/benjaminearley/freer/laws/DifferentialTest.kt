package com.benjaminearley.freer.laws

import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The optimized interpreter against the model: random programs over every effect family,
 * under random complete handler stacks that mix every handler style the library offers.
 */
class DifferentialTest {
    @Test
    fun libraryAgreesWithTheModel() =
        runTest(timeout = 10.minutes) {
            val cases = LAW_ITERATIONS * 4
            val run =
                checkAll(cases, arbExpr(), arbStack()) { expr, stack ->
                    assertEquals(observe(stack, expr.toRef()), observe(stack, expr.toProgram()), "program $expr under $stack")
                }
            // Guards against a platform where the property loop runs fewer cases than asked.
            assertEquals(cases, run.attempts())
        }

    @Test
    fun libraryAgreesWithTheModelOnDeeperPrograms() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(depth = 7), arbStack()) { expr, stack ->
                assertEquals(observe(stack, expr.toRef()), observe(stack, expr.toProgram()), "program $expr under $stack")
            }
        }
}
