/**
 * Trampoline stack-safety tests. Each scenario runs N = 100,000 effects (or binds)
 * through a differently shaped program/handler stack that could overflow the
 * stack: direct-resume trampolining, the handle DSL's Done-path optimization,
 * effect forwarding past a non-matching handler, stateful interpretation, deferred
 * resumes via intercept, left-associated bind chains, deep non-tail recursion,
 * long chains of pure binds, bind chains queued on a deferred head, resume bound
 * inside a program{} block (deferred-context resume), sequential nondeterminism
 * through collectAll, effect-free non-tail bind recursion, nested program{}
 * recursion that performs no effect, and recursion that binds before performing
 * an effect.
 */

package com.benjaminearley.freer

import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import kotlin.test.Test
import kotlin.test.assertEquals

private sealed interface Counter<out R> : Effect<R>

private data object Increment : Counter<Unit>

// An unrelated effect that forces forwarding
private sealed interface Unrelated<out R> : Effect<R>

private data object Noop : Unrelated<Unit>

private const val N = 100_000 // Well beyond the ~5-10K stack limit

// Performs N Increment effects from a program { } block and counts them.
private fun countIncrements(): Program<Int> =
    program {
        var count = 0
        repeat(N) {
            perform(Increment).bind()
            count++
        }
        count
    }

class TrampolineTest {
    // 1.
    @Test
    fun interpretLowLevelDirectResume() {
        // Handle with raw interpret — resume called directly → trampoline
        val result =
            countIncrements()
                .interpret<Counter<*>, Int> { op, resume ->
                    when (op) {
                        is Increment -> resume(Unit)
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 2.
    @Test
    fun handleDslDonePathOptimization() {
        // Handle with DSL — program { rule(op) } returns Done → resume called directly
        val result =
            countIncrements()
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 3.
    @Test
    fun handleWithForwardingPastNonMatchingHandler() {
        // Increment effects are forwarded past the Unrelated handler, then caught by Counter handler.
        // Each forwarded effect creates a Pipeline.Step with a deferred interpreterLoop call.
        // The outer handler's trampoline keeps the stack flat.
        val result =
            countIncrements()
                .handle<Unrelated<*>, Int> { op ->
                    when (op) {
                        is Noop -> Unit
                    }
                }.handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 4.
    @Test
    fun interpretSStatefulHandlerExtractsFinalState() {
        val prog =
            program {
                repeat(N) {
                    perform(Increment).bind()
                }
            }

        // Stateful handler: counts how many Increments were handled.
        // Uses interpretS directly with transformDone to extract the final count.
        val count =
            prog
                .interpretS<Counter<*>, Int, Unit, Int>(
                    initialState = 0,
                    transformDone = { s, _ -> Program.Done(s) },
                ) { s, op, resume ->
                    when (op) {
                        is Increment -> resume(s + 1, Unit)
                    }
                }.runOrThrow()
        assertEquals(N, count)
    }

    // 5.
    @Test
    fun interceptThenHandleDeferredResume() {
        // Middleware intercepts every Increment (re-emits via proceed), then handle consumes it.
        // proceed() calls perform(op) → Suspended path → deferred resume.
        // Stack safety comes from the deferred path resetting the stack.
        val result =
            countIncrements()
                .intercept<Counter<*>, Int> { op, proceed ->
                    when (op) {
                        is Increment -> proceed()
                    }
                }.handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 6.
    @Test
    fun leftAssociatedFlatMapChainThenHandle() {
        // Build a deeply left-associated chain — the worst case for both
        // the type-aligned queue AND the trampoline.
        var prog: Program<Int> = Program.Done(0)
        for (i in 1..N) {
            prog =
                prog.flatMap { count ->
                    perform(Increment).map { count + 1 }
                }
        }

        val result =
            prog
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 7.
    @Test
    fun deepNonTailRecursionWithWorkAfterSuspendingBind() {
        // Each level does work AFTER a bind that suspends, so the remaining
        // pipeline is concatenated N levels deep. The unwind of the innermost
        // Done must stay iterative — nested resume closures would overflow
        // around depth ~5K.
        fun build(remaining: Int): Program<Int> =
            if (remaining == 0) {
                perform(Increment).map { 0 }
            } else {
                perform(Increment).flatMap { build(remaining - 1) }.map { it + 1 }
            }

        val result =
            build(N)
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 8.
    @Test
    fun longChainsOfPureAlreadyDoneBinds() {
        // Consecutive binds of Done programs never suspend, so the interpreter
        // trampoline never sees them — buildProgram must consume them in a
        // loop, both at construction time (pure-only program) and after an
        // effectful bind (chain unrolls during interpretation).
        val pureOnly =
            program {
                var count = 0
                repeat(N) {
                    count += Program.Done(1).bind()
                }
                count
            }
        assertEquals(N, pureOnly.runOrThrow())

        val afterEffect =
            program {
                perform(Increment).bind()
                var count = 0
                repeat(N) {
                    count += Program.Done(1).bind()
                }
                count
            }
        val interpreted =
            afterEffect
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, interpreted)
    }

    // 9.
    @Test
    fun longFlatMapChainsOntoProgramHeadUseDeferPendingQueue() {
        // A Defer head (program{}, or any handled program) used to nest one
        // closure per bind, overflowing around depth ~10-20K when forced. Binds
        // now queue on the Defer's pending pipeline instead.
        var prog: Program<Int> = program { 0 }
        repeat(N) {
            prog = prog.flatMap { count -> Program.Done(count + 1) }
        }
        assertEquals(N, prog.runOrThrow())
        // The pending queue is immutable, so a replay must match
        assertEquals(N, prog.runOrThrow())
    }

    // 10.
    @Test
    fun longMapChainsOntoProgramHead() {
        var prog: Program<Int> = program { 0 }
        repeat(N) {
            prog = prog.map { it + 1 }
        }
        assertEquals(N, prog.runOrThrow())
    }

    // 11.
    @Test
    fun longFlatMapChainsOntoAlreadyHandledHead() {
        // Every applied handler returns a deferred program, so this is the same
        // shape as test 9 reached through the public handler API.
        var prog: Program<Int> =
            perform(Increment)
                .map { 0 }
                .interpret<Counter<*>, Int> { op, resume ->
                    when (op) {
                        is Increment -> resume(Unit)
                    }
                }
        repeat(N) {
            prog = prog.flatMap { count -> Program.Done(count + 1) }
        }
        assertEquals(N, prog.runOrThrow())
    }

    // 12.
    @Test
    fun interpretWithResumeBoundInsideProgramBlock() {
        // The documented way to post-process a continuation's result: call resume
        // from a deferred context. Every effect then goes through a program{} thunk
        // that binds the rest of the interpretation — a deferred program that must
        // not be forced in place, or each effect nests a frame.
        val result =
            countIncrements()
                .interpret<Counter<*>, Int> { op, resume ->
                    when (op) {
                        is Increment -> program { resume(Unit).bind() }
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }

    // 13.
    @Test
    fun sequentialNondeterminismDepthThroughCollectAll() {
        // N single-option chooses. collectAll resumes each continuation from a
        // program{} block, so N chained choices are N deferred-context resumes in
        // a row. A hand-built chain, not a program{} block: collectAll is multi-shot.
        var prog: Program<Int> = Program.Done(0)
        repeat(N) {
            prog = prog.flatMap { count -> choose(listOf(1)).map { count + it } }
        }
        assertEquals(listOf(N), prog.collectAll().runOrThrow())
    }

    // 14.
    @Test
    fun effectFreeNonTailBindRecursion() {
        // flatMap into a deferred head, map after. No effect is ever performed, so
        // no interpreter loop is involved: the step bound on the program{} head
        // returns the next level's deferred program, which must be queued rather
        // than forced.
        fun rec(n: Int): Program<Int> =
            if (n == 0) {
                Program.Done(0)
            } else {
                program { 0 }.flatMap { rec(n - 1) }.map { it + 1 }
            }

        assertEquals(N, rec(N).runOrThrow())
    }

    // 15.
    @Test
    fun nestedProgramBlockRecursionWithoutAnEffect() {
        // Each level binds the next level's program{} block and does work after
        // it, so N deferred programs are nested with no effect in between.
        fun rec(k: Int): Program<Int> =
            if (k == 0) {
                Program.Done(0)
            } else {
                program { rec(k - 1).bind() + 1 }
            }

        assertEquals(N, rec(N).runOrThrow())
    }

    // 16.
    @Test
    fun recursiveBindBeforeTheEffect() {
        // program{} recursion, then perform. The recursive bind comes first, so all
        // N levels are entered before the first effect is performed; the
        // effect-driven unwind then runs under the handler's trampoline.
        fun rec(k: Int): Program<Int> =
            if (k == 0) {
                Program.Done(0)
            } else {
                program {
                    val r = rec(k - 1).bind()
                    perform(Increment).bind()
                    r + 1
                }
            }

        val result =
            rec(N)
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        assertEquals(N, result)
    }
}
