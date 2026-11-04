package com.benjaminearley.freer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stack-safety scenarios on a thread with a deliberately small stack, so that the
 * tests cannot pass by the default stack happening to be large enough. A control test
 * first checks that the small stack really is small.
 */
class SmallStackTest {
    private fun onSmallStack(block: () -> Unit) {
        var failure: Throwable? = null
        val thread =
            Thread(null, {
                try {
                    block()
                } catch (t: Throwable) {
                    failure = t
                }
            }, "small-stack", SMALL_STACK_BYTES)
        thread.start()
        thread.join()
        failure?.let { throw it }
    }

    // How many frames of plain recursion fit before the stack overflows.
    private fun framesUntilOverflow(): Int {
        var frames = 0

        fun recurse() {
            frames++
            recurse()
        }
        try {
            recurse()
        } catch (_: StackOverflowError) {
        }
        return frames
    }

    @Test
    fun theSmallStackIsSmall() {
        var small = 0
        onSmallStack { small = framesUntilOverflow() }
        val default = framesUntilOverflow()
        println("frames of a trivial recursion before overflow: $small on the small stack, $default on the default stack")
        assertTrue(small < default / 2, "the small stack held $small frames, the default $default")
        assertTrue(small < 20_000, "the small stack held $small frames")
    }

    private data object Probe : Effect<Int>

    private data object Unused : Effect<Int>

    // The largest power of two of nested handler applications that the small stack runs.
    @Test
    fun nestedHandlersThatFitTheSmallStack() {
        var fits = 0
        var levels = 64
        while (levels <= 1 shl 20) {
            var overflowed = false
            onSmallStack {
                var program: Program<Int> = perform(Probe).interpret<Probe, Int> { _, resume -> resume(1) }
                repeat(levels) { program = program.interpret<Unused, Int> { _, resume -> resume(0) } }
                try {
                    program.runOrThrow()
                } catch (_: StackOverflowError) {
                    overflowed = true
                }
            }
            if (overflowed) break
            fits = levels
            levels *= 2
        }
        println("nested handlers that fit a ${SMALL_STACK_BYTES / 1024} KB stack: at least $fits, fewer than $levels")
        assertTrue(fits >= 64, "only $fits nested handlers fit the small stack")
    }

    @Test
    fun everyScenarioRunsOnTheSmallStack() {
        for (scenario in trampolineScenarios) {
            try {
                onSmallStack { scenario.run(N) }
            } catch (t: Throwable) {
                throw AssertionError("'$scenario' failed on a ${SMALL_STACK_BYTES / 1024} KB stack", t)
            }
        }
    }

    private companion object {
        const val SMALL_STACK_BYTES = 256L * 1024
        const val N = 100_000
    }
}
