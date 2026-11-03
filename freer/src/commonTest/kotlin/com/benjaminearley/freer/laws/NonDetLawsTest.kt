package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.ifte
import com.benjaminearley.freer.effects.msplit
import com.benjaminearley.freer.effects.once
import com.benjaminearley.freer.effects.orElse
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.map
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The equations of choice and failure, of choose as a choice over a list, and of the
 * committed-choice operations built on msplit.
 */
class NonDetLawsTest {
    private val arbContinuation = arbExpr(scope = 1)
    private val arbOptions: Arb<List<Int>> = Arb.list(arbSmallInt, 0..3)

    private fun <A> mzero(): Program<A> = choose(emptyList())

    private fun Expr.asContinuation(): (Int) -> Program<Int> = { value -> toProgram(listOf(value)) }

    private fun <A> unsplit(split: Pair<A, Program<A>>?): Program<A> =
        if (split == null) mzero() else orElse(Program.Done(split.first), split.second)

    // The first answer and all the rest, as plain data.
    private fun <A> reify(search: Program<A>): Program<Pair<A, List<A>>?> =
        search.msplit().flatMap { split ->
            if (split == null) Program.Done(null) else split.second.collectAll().map { rest -> split.first to rest }
        }

    @Test
    fun orElseIsAssociative() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTriple(arbExpr(), arbExpr(), arbExpr()), arbStack()) { (a, b, c), stack ->
                val (pa, pb, pc) = Triple(a.toProgram(), b.toProgram(), c.toProgram())
                assertSameUnder(stack, orElse(orElse(pa, pb), pc), orElse(pa, orElse(pb, pc)))
            }
        }

    @Test
    fun noChoiceIsTheIdentityOfOrElse() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbStack()) { a, stack ->
                val pa = a.toProgram()
                assertSameUnder(stack, orElse(mzero(), pa), pa)
                assertSameUnder(stack, orElse(pa, mzero()), pa)
            }
        }

    @Test
    fun noChoiceIsALeftZeroOfBind() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbContinuation, arbStack()) { k, stack ->
                assertSameUnder(stack, mzero<Int>().flatMap(k.asContinuation()), mzero<Int>())
            }
        }

    @Test
    fun bindDistributesOverOrElse() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbExpr(), arbExpr()), arbContinuation, arbStack()) { (a, b), k, stack ->
                val (pa, pb) = a.toProgram() to b.toProgram()
                val f = k.asContinuation()
                assertSameUnder(stack, orElse(pa, pb).flatMap(f), orElse(pa.flatMap(f), pb.flatMap(f)))
            }
        }

    @Test
    fun chooseIsNestedOrElse() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbOptions, arbStack()) { options, stack ->
                assertSameUnder(stack, choose(options), options.foldRight(mzero<Int>()) { x, rest -> orElse(Program.Done(x), rest) })
            }
        }

    @Test
    fun bindDistributesOverChoose() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbOptions, arbContinuation, arbStack()) { options, k, stack ->
                val f = k.asContinuation()
                assertSameUnder(stack, choose(options).flatMap(f), options.foldRight(mzero<Int>()) { x, rest -> orElse(f(x), rest) })
            }
        }

    @Test
    fun splittingNoChoiceFindsNothing() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbStack()) { stack ->
                assertSameUnder(stack, reify(mzero<Int>()), Program.Done(null))
            }
        }

    @Test
    fun splittingAnAnswerInFrontFindsItAndTheRest() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbSmallInt, arbExpr()), arbStack()) { (a, m), stack ->
                val rest = m.toProgram()
                assertSameUnder(stack, reify(orElse(Program.Done(a), rest)), rest.collectAll().map { a to it })
            }
        }

    @Test
    fun onceCommitsToTheAnswerInFront() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbSmallInt, arbExpr()), arbStack()) { (a, m), stack ->
                assertSameUnder(stack, once(orElse(Program.Done(a), m.toProgram())), Program.Done(a))
                assertSameUnder(stack, once(mzero<Int>()), mzero<Int>())
            }
        }

    @Test
    fun softCutRunsTheThenBranchOnAnAnswerAndTheOtherwiseOnNone() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbPair(arbSmallInt, arbExpr()), arbContinuation, arbStack()) { (a, el), k, stack ->
                val then = k.asContinuation()
                val otherwise = el.toProgram()
                assertSameUnder(stack, ifte(Program.Done(a), then, otherwise), then(a))
                assertSameUnder(stack, ifte(mzero(), then, otherwise), otherwise)
            }
        }

    // The soft cut splits its condition itself, so the condition's own choices are made by
    // that split, inside every handler of the stack, where on the right-hand side they are
    // made by the stack's nondeterminism handler. A handler inside that one which keeps
    // state or cuts a program short therefore sees the two sides differently: a failure in
    // the rest of the condition abandons all of its remaining options on the left and one
    // option on the right. With only read-only handlers inside, the sides agree.
    @Test
    fun softCutOverAnAnswerInFrontWithOnlyReadOnlyHandlersInside() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbTriple(arbSmallInt, arbExpr(), arbExpr()), arbContinuation, arbStack()) { (a, m, el), k, stack ->
                val inside = stack.handlers.take(stack.indexOf(Family.NONDET))
                if (inside.all { it.readOnly }) {
                    val then = k.asContinuation()
                    val otherwise = el.toProgram()
                    val rest = m.toProgram()
                    assertSameUnder(stack, ifte(orElse(Program.Done(a), rest), then, otherwise), orElse(then(a), rest.flatMap(then)))
                }
            }
        }

    // Splitting runs the search up to its first answer before the rest is re-joined, so a
    // handler inside the nondeterminism handler that keeps state or cuts a program short sees
    // the search in a different order. With only read-only handlers inside, splitting and
    // re-joining is invisible.
    @Test
    fun splitThenUnsplitIsInvisibleWithOnlyReadOnlyHandlersInside() =
        runTest(timeout = 10.minutes) {
            checkAll(LAW_ITERATIONS, arbExpr(), arbStack()) { expr, stack ->
                val inside = stack.handlers.take(stack.indexOf(Family.NONDET))
                if (inside.all { it.readOnly }) {
                    val m = expr.toProgram()
                    assertSameUnder(stack, m, m.msplit().flatMap { unsplit(it) })
                }
            }
        }
}
