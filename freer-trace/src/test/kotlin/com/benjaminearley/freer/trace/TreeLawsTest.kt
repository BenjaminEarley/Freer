package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.trace.mermaid.flowchart
import com.benjaminearley.freer.effects.NonDet
import com.benjaminearley.freer.effects.Reader
import com.benjaminearley.freer.effects.State
import com.benjaminearley.freer.laws.Expr
import com.benjaminearley.freer.laws.LAW_ITERATIONS
import com.benjaminearley.freer.laws.Tick
import com.benjaminearley.freer.laws.arbExpr
import com.benjaminearley.freer.laws.toBlock
import com.benjaminearley.freer.laws.toProgram
import com.benjaminearley.freer.laws.toRef
import com.benjaminearley.freer.reference.Ref
import com.benjaminearley.freer.reference.choose
import com.benjaminearley.freer.reference.collectAll
import com.benjaminearley.freer.reference.flatMap
import com.benjaminearley.freer.reference.handleRelay
import com.benjaminearley.freer.reference.pure
import com.benjaminearley.freer.reference.run
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

/**
 * The path explorer against the library's specification interpreter, on the random programs
 * of the library's law tests. Soundness: every leaf of the tree is what the model computes when
 * a scripted handler answers from that path. Completeness: the leaves, in order, are what the
 * model computes when every effect is answered by a nondeterministic choice over the same
 * responses, which visits every assignment of answers. And a program block has the tree of
 * the same program built with flatMap.
 */
class TreeLawsTest {
    private companion object {
        const val MAX_PATHS = 4096L
    }

    // Tick, Get and Ask respond with Int or Any?, which have no finite set of values, and Choose
    // with one of its options; Put and Raise are unfolded by their response types.
    private fun Representatives.forLaws() {
        ofEffect<Tick>(0, 1)
        ofEffect<State.Get>(-1, 2)
        ofEffect<Reader.Ask>(0, 3)
        ofEffect { if (it is NonDet.Choose) it.options else null }
    }

    // A tree holds every path, so its size is the product of the response counts of the
    // effects along the way; a random program with a few dozen effects can have millions of
    // paths and exhaust the test JVM. The generated programs are kept to a bounded product.
    private val arbTreeExpr = arbExpr().filter { it.pathBound() <= MAX_PATHS }

    // An upper bound on the number of paths: the product, over the program's effects, of the
    // responses forLaws gives each. A failure or an empty choice ends its path and counts one.
    private fun Expr.pathBound(): Long =
        when (this) {
            is Expr.Lit, is Expr.Arg, is Expr.Fail -> 1
            Expr.Step, Expr.Read, Expr.Env -> 2
            is Expr.Pick -> maxOf(1, options.size).toLong()
            is Expr.Write -> value.pathBound()
            is Expr.Sum -> left.pathBound() * right.pathBound()
            is Expr.Seq -> first.pathBound() * rest.pathBound()
        }

    // The same responses, for the model's handlers.
    private fun optionsOf(effect: Effect<*>): List<Any?> =
        when (effect) {
            Tick -> listOf(0, 1)
            State.Get -> listOf(-1, 2)
            Reader.Ask -> listOf(0, 3)
            is NonDet.Choose -> effect.options
            else -> responses(effect) ?: error("no responses for $effect")
        }

    // How the model ends a path at an effect with no response: in the words of the tree's leaves.
    private fun stops(effect: Effect<*>): Ref<String> = pure(Tree.Stops(effect).outcome())

    @Test
    fun everyLeafIsWhatTheModelComputesUnderAHandlerAnsweringFromItsPath() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTreeExpr) { expr ->
                for (path in expr.toProgram().tree { forLaws() }.paths()) {
                    val script = path.answers.iterator()
                    val modelled =
                        expr
                            .toRef()
                            .handleRelay<Effect<*>, Int, String>({ pure("returns $it") }) { op, resume ->
                                if (optionsOf(op).isEmpty()) {
                                    stops(op)
                                } else {
                                    val answer = script.next()
                                    assertEquals(answer.effect.toString(), op.toString(), "path ${path.answers} of $expr")
                                    resume(answer.response)
                                }
                            }.run()
                    assertEquals(path.end.outcome(), modelled, "path ${path.answers} of $expr")
                    assertFalse(script.hasNext(), "the model performed fewer effects than path ${path.answers} of $expr")
                }
            }
        }

    @Test
    fun everyAssignmentOfAnswersReachesALeaf() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTreeExpr) { expr ->
                val modelled =
                    expr
                        .toRef()
                        .handleRelay<Effect<*>, Int, String>({ pure("returns $it") }) { op, resume ->
                            val options = optionsOf(op)
                            if (options.isEmpty()) stops(op) else choose(options).flatMap(resume)
                        }.collectAll()
                        .run()
                assertEquals(modelled, expr.toProgram().tree { forLaws() }.paths().map { it.end.outcome() }, "program $expr")
            }
        }

    @Test
    fun aBlockHasTheTreeOfTheSameProgramBuiltWithFlatMap() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTreeExpr) { expr ->
                assertEquals(expr.toProgram().tree { forLaws() }.flowchart(), expr.toBlock().tree { forLaws() }.flowchart(), "program $expr")
            }
        }
}
