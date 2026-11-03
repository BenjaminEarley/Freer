package com.benjaminearley.freer.laws

import com.benjaminearley.freer.program
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * program{} blocks against flatMap chains. A block is a one-shot coroutine, so binding a
 * sub-program inside it equals sequencing that sub-program with flatMap exactly as long as
 * the sub-program produces its value at most once; the second time, the block fails with
 * the single-shot error instead of silently re-running with stale locals.
 */
class ProgramBlockTest {
    @Test
    fun blockEqualsProgramWithoutChoice() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(Alphabet(choose = false)), arbStack()) { expr, stack ->
                val expected = observe(stack, expr.toRef())
                assertEquals(expected, observe(stack, program { expr.toProgram().bind() }), "outer block of $expr under $stack")
                assertEquals(expected, observe(stack, expr.toBlock()), "block form of $expr under $stack")
            }
        }

    @Test
    fun blockFailsExactlyWhenABoundProgramProducesItsValueTwice() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbStack()) { expr, stack ->
                val completions = mutableMapOf<String, Int>()
                val expected = observe(stack, expr.toRef(completed = { path -> completions[path] = (completions[path] ?: 0) + 1 }))

                // program { p.bind() } binds the whole program once: it fails if the root completes twice.
                val outerBlock = runCatching { observe(stack, program { expr.toProgram().bind() }) }
                if ((completions[""] ?: 0) >= 2) {
                    assertSingleShotFailure(outerBlock)
                } else {
                    assertEquals(expected, outerBlock.getOrThrow(), "outer block of $expr under $stack")
                }

                // The block form binds every sub-program: it fails if any of them completes twice.
                val blockForm = runCatching { observe(stack, expr.toBlock()) }
                if (completions.values.any { it >= 2 }) {
                    assertSingleShotFailure(blockForm)
                } else {
                    assertEquals(expected, blockForm.getOrThrow(), "block form of $expr under $stack")
                }
            }
        }
}
