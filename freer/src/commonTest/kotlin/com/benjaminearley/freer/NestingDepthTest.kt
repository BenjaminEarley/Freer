package com.benjaminearley.freer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant behind stack safety, checked directly: how deeply force() and the
 * interpreter loops nest inside one another depends on the shape of the handler stack
 * and not on the length of the program. Every scenario runs at two lengths with the
 * nesting tracked, and the deepest nesting must be the same at both.
 */
class NestingDepthTest {
    private fun deepestNesting(
        scenario: TrampolineScenario,
        n: Int,
    ): Int {
        Nesting.depth = 0
        Nesting.deepest = 0
        Nesting.tracking = true
        try {
            scenario.run(n)
        } finally {
            Nesting.tracking = false
        }
        return Nesting.deepest
    }

    @Test
    fun nestingDepthDoesNotGrowWithProgramLength() {
        val report = StringBuilder("deepest nesting of force() and the interpreter loops, per scenario:\n")
        for (scenario in trampolineScenarios) {
            val short = deepestNesting(scenario, 100)
            val long = deepestNesting(scenario, 20_000)
            report.append("  $long  $scenario\n")
            assertEquals(short, long, "the nesting depth of '$scenario' grew with the program's length")
            // At most two handlers are nested in any scenario, and each handler is one
            // interpreter loop on one forced program.
            assertTrue(long <= DEEPEST_EXPECTED, "'$scenario' nested $long deep")
        }
        println(report)
    }

    private companion object {
        const val DEEPEST_EXPECTED = 5
    }
}
