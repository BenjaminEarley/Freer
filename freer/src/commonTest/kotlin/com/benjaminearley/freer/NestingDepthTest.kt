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
        // Every entry has its exit, so the depth is back at zero once the run is over.
        assertEquals(0, Nesting.depth, "the nesting of '$scenario' is unbalanced after the run")
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
            // Every scenario forces at least one program; at most two handlers are nested in
            // any of them, and each handler is one interpreter loop on one forced program.
            assertTrue(long in 1..DEEPEST_EXPECTED, "'$scenario' nested $long deep")
        }
        println(report)
    }

    @Test
    fun theDepthIsVisibleWhileAProgramRuns() {
        var seen = -1
        Nesting.depth = 0
        Nesting.deepest = 0
        Nesting.tracking = true
        try {
            perform(AskInt).handle<Ask<*>, Int> { seen = Nesting.depth; 1 }.runOrThrow()
        } finally {
            Nesting.tracking = false
        }
        // The force() of the handled program, then the loop of its one handler.
        assertEquals(2, seen)
        assertEquals(2, Nesting.deepest)
        assertEquals(0, Nesting.depth)
    }

    @Test
    fun theDeepestNestingIsAMaximumNotTheLastDepth() {
        Nesting.depth = 0
        Nesting.deepest = 0
        Nesting.tracking = true
        try {
            // Two nested handlers: a force() and a loop for the outer one, then the same
            // again for the inner one. Then one handler, two levels, which must not lower it.
            perform(AskInt).handle<Ask<*>, Int> { 1 }.handle<Out<*>, Int> { }.runOrThrow()
            perform(AskInt).handle<Ask<*>, Int> { 1 }.runOrThrow()
        } finally {
            Nesting.tracking = false
        }
        assertEquals(4, Nesting.deepest)
        assertEquals(0, Nesting.depth)
    }

    private companion object {
        const val DEEPEST_EXPECTED = 5
    }
}
