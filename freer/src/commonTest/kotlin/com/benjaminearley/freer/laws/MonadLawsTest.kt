package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.program
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** The monad laws for Program, under flatMap and under program{} with bind(). */
class MonadLawsTest {
    // A continuation: a program with one value in scope.
    private val arbContinuation = arbExpr(scope = 1)
    private val arbContinuationWithoutChoice = arbExpr(Alphabet(choose = false), scope = 1)

    private fun Expr.asContinuation(): (Int) -> Program<Int> = { value -> toProgram(listOf(value)) }

    @Test
    fun leftIdentity() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbSmallInt, arbContinuation, arbStack()) { a, k, stack ->
                val f = k.asContinuation()
                assertSameUnder(stack, f(a), Program.Done(a).flatMap(f))
            }
        }

    @Test
    fun rightIdentity() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbStack()) { expr, stack ->
                val p = expr.toProgram()
                assertSameUnder(stack, p, p.flatMap { Program.Done(it) })
            }
        }

    @Test
    fun associativity() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), Arb.bind(arbContinuation, arbContinuation) { f, g -> f to g }, arbStack()) { expr, (kf, kg), stack ->
                val p = expr.toProgram()
                val f = kf.asContinuation()
                val g = kg.asContinuation()
                assertSameUnder(stack, p.flatMap(f).flatMap(g), p.flatMap { f(it).flatMap(g) })
            }
        }

    @Test
    fun leftIdentityInBlocks() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbSmallInt, arbContinuationWithoutChoice, arbStack()) { a, k, stack ->
                val f = k.asContinuation()
                assertSameUnder(
                    stack,
                    f(a),
                    program {
                        val x = Program.Done(a).bind()
                        f(x).bind()
                    },
                )
            }
        }

    @Test
    fun rightIdentityInBlocks() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(Alphabet(choose = false)), arbStack()) { expr, stack ->
                val p = expr.toProgram()
                assertSameUnder(
                    stack,
                    p,
                    program {
                        val x = p.bind()
                        Program.Done(x).bind()
                    },
                )
            }
        }

    @Test
    fun associativityInBlocks() =
        runTest(timeout = 10.minutes) {
            checkAll(
                LAW_ITERATIONS,
                arbExpr(Alphabet(choose = false)),
                Arb.bind(arbContinuationWithoutChoice, arbContinuationWithoutChoice) { f, g -> f to g },
                arbStack(),
            ) { expr, (kf, kg), stack ->
                val p = expr.toProgram()
                val f = kf.asContinuation()
                val g = kg.asContinuation()
                assertSameUnder(
                    stack,
                    program {
                        val x = p.bind()
                        val y = f(x).bind()
                        g(y).bind()
                    },
                    program {
                        val y =
                            program {
                                val x = p.bind()
                                f(x).bind()
                            }.bind()
                        g(y).bind()
                    },
                )
            }
        }
}
