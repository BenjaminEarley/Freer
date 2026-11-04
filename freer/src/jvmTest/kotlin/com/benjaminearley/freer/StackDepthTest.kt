package com.benjaminearley.freer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many JVM frames each nested handler application costs. A handler is an interpreter
 * loop that stays on the stack while its inner program runs, so the frames on the stack
 * inside the innermost rule grow with the number of handlers applied outside it. The test
 * measures that growth for every handler style, on the first entry into the handlers and
 * again after an effect has been relayed through all of them, and checks it is linear.
 * The numbers it prints are the ones the README states.
 */
class StackDepthTest {
    private data object Probe : Effect<Int>

    private data object Relayed : Effect<Int>

    private data object Unused : Effect<Int>

    private fun framesHere(): Int = StackWalker.getInstance().walk { frames -> frames.count().toInt() }

    // The frames on the stack inside the Probe rule, with [levels] handlers of one style
    // applied outside it. With [afterRelay], the probe follows an effect that the outermost
    // handler answers, so the levels are re-entered through their relay steps first.
    private fun framesInsideRule(
        levels: Int,
        afterRelay: Boolean,
        style: (Program<Int>) -> Program<Int>,
    ): Int {
        var measured = -1
        val probe: Program<Int> = if (afterRelay) perform(Relayed).flatMap { perform(Probe) } else perform(Probe)
        var program = probe.interpret<Probe, Int> { _, resume ->
            measured = framesHere()
            resume(0)
        }
        repeat(levels) { program = style(program) }
        program.interpret<Relayed, Int> { _, resume -> resume(0) }.runOrThrow()
        check(measured >= 0)
        return measured
    }

    private val styles: Map<String, (Program<Int>) -> Program<Int>> =
        mapOf(
            "interpret" to { p -> p.interpret<Unused, Int> { _, resume -> resume(0) } },
            "interpretS" to { p -> p.interpretS<Unused, Int, Int>(0) { s, _, resume -> resume(s, 0) } },
            "handle" to { p -> p.handle<Unused, Int> { 0 } },
            "handleS" to { p -> p.handleS<Unused, Int, Int>(0) { s, _ -> s to 0 } },
            "intercept" to { p -> p.intercept<Unused, Int> { _, proceed -> proceed() } },
        )

    private fun framesPerLevel(
        afterRelay: Boolean,
        style: (Program<Int>) -> Program<Int>,
    ): Int {
        val at = listOf(4, 8, 16, 32).associateWith { levels -> framesInsideRule(levels, afterRelay, style) }
        val perLevel = (at.getValue(32) - at.getValue(16)) / 16
        assertEquals(perLevel * 8, at.getValue(16) - at.getValue(8), "frames per level are not constant: $at")
        assertEquals(perLevel * 4, at.getValue(8) - at.getValue(4), "frames per level are not constant: $at")
        return perLevel
    }

    @Test
    fun framesGrowLinearlyWithNestedHandlers() {
        val report = StringBuilder("frames per nested handler level (first entry / after a relay):\n")
        var worst = 0
        for ((name, style) in styles) {
            val entry = framesPerLevel(afterRelay = false, style)
            val relayed = framesPerLevel(afterRelay = true, style)
            report.append("  $name: $entry / $relayed\n")
            worst = maxOf(worst, entry, relayed)
        }
        println(report)
        assertTrue(worst <= FRAMES_PER_LEVEL_BOUND, "a handler level costs $worst frames, more than $FRAMES_PER_LEVEL_BOUND")
    }

    private companion object {
        const val FRAMES_PER_LEVEL_BOUND = 24
    }
}
