package com.benjaminearley.freer.laws

import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The stateful interpreter adds nothing the stateless one cannot express: a handler that
 * threads its state explicitly, as a function of the state in the result, gives the same
 * answers wherever it sits in the stack.
 */
class InterpretSTest {
    @Test
    fun interpretSEqualsInterpretWithExplicitState() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbSlot(missing = Family.TICK)) { expr, slot ->
                val p = expr.toProgram()
                assertSameUnder(slot.fill(countTicksHandler), p, p)
                kotlin.test.assertEquals(
                    observe(slot.fill(countTicksHandler), p),
                    observe(slot.fill(countTicksByFunctionsHandler), p),
                    "program $expr in $slot",
                )
            }
        }
}
