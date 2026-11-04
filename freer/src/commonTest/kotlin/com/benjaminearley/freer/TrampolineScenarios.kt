/**
 * Stack-safety scenarios. Each runs n effects (or binds) through a differently shaped
 * program and handler stack that could overflow the stack: direct-resume trampolining,
 * the handle DSL's Done-path optimization, effect forwarding past a non-matching handler,
 * stateful interpretation, deferred resumes via intercept, left-associated bind chains,
 * deep non-tail recursion, long chains of pure binds, bind chains queued on a deferred
 * head, resume bound inside a program{} block (deferred-context resume), sequential
 * nondeterminism through collectAll, effect-free non-tail bind recursion, nested
 * program{} recursion that performs no effect, and recursion that binds before
 * performing an effect. The scenarios are data so that several tests can run them: at
 * full length on the default stack, on a deliberately small stack, and at two lengths
 * with the nesting depth tracked.
 */

package com.benjaminearley.freer

import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import kotlin.test.assertEquals

class TrampolineScenario(
    val name: String,
    val run: (n: Int) -> Unit,
) {
    override fun toString(): String = name
}

private sealed interface Counter<out R> : Effect<R>

private data object Increment : Counter<Unit>

// An unrelated effect that forces forwarding
private sealed interface Unrelated<out R> : Effect<R>

private data object Noop : Unrelated<Unit>

// Performs n Increment effects from a program { } block and counts them.
private fun countIncrements(n: Int): Program<Int> =
    program {
        var count = 0
        repeat(n) {
            perform(Increment).bind()
            count++
        }
        count
    }

private fun <A> Program<A>.handleIncrements(): Program<A> =
    handle<Counter<*>, A> { op ->
        when (op) {
            is Increment -> Unit
        }
    }

val trampolineScenarios: List<TrampolineScenario> =
    listOf(
        // 1. Handle with raw interpret: resume called directly, so the trampoline runs.
        TrampolineScenario("interpret with a direct resume") { n ->
            val result =
                countIncrements(n)
                    .interpret<Counter<*>, Int> { op, resume ->
                        when (op) {
                            is Increment -> resume(Unit)
                        }
                    }.runOrThrow()
            assertEquals(n, result)
        },
        // 2. Handle with the DSL: the rule returns without suspending, so resume is called directly.
        TrampolineScenario("handle on the Done path") { n ->
            assertEquals(n, countIncrements(n).handleIncrements().runOrThrow())
        },
        // 3. Increments are forwarded past the Unrelated handler, then caught by the Counter
        // handler. Each forwarded effect creates a step that re-enters the inner loop; the
        // outer handler's trampoline keeps the stack flat.
        TrampolineScenario("handle with forwarding past a non-matching handler") { n ->
            val result =
                countIncrements(n)
                    .handle<Unrelated<*>, Int> { op ->
                        when (op) {
                            is Noop -> Unit
                        }
                    }.handleIncrements()
                    .runOrThrow()
            assertEquals(n, result)
        },
        // 4. A stateful handler counts how many Increments it handled and extracts the count.
        TrampolineScenario("interpretS extracting the final state") { n ->
            val prog =
                program {
                    repeat(n) {
                        perform(Increment).bind()
                    }
                }
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
            assertEquals(n, count)
        },
        // 5. Middleware re-emits every Increment via proceed, then handle consumes it. proceed
        // suspends the rule block, so every effect is a deferred resume.
        TrampolineScenario("intercept then handle, a deferred resume per effect") { n ->
            val result =
                countIncrements(n)
                    .intercept<Counter<*>, Int> { op, proceed ->
                        when (op) {
                            is Increment -> proceed()
                        }
                    }.handleIncrements()
                    .runOrThrow()
            assertEquals(n, result)
        },
        // 6. A deeply left-associated chain: the worst case for the queue and the trampoline.
        TrampolineScenario("left-associated flatMap chain then handle") { n ->
            var prog: Program<Int> = Program.Done(0)
            for (i in 1..n) {
                prog =
                    prog.flatMap { count ->
                        perform(Increment).map { count + 1 }
                    }
            }
            assertEquals(n, prog.handleIncrements().runOrThrow())
        },
        // 7. Each level does work after a bind that suspends, so the remaining pipeline is
        // concatenated n levels deep; the unwind of the innermost Done must stay iterative.
        TrampolineScenario("deep non-tail recursion with work after a suspending bind") { n ->
            fun build(remaining: Int): Program<Int> =
                if (remaining == 0) {
                    perform(Increment).map { 0 }
                } else {
                    perform(Increment).flatMap { build(remaining - 1) }.map { it + 1 }
                }
            assertEquals(n, build(n).handleIncrements().runOrThrow())
        },
        // 8. Consecutive binds of Done programs never suspend, so the trampoline never sees
        // them; the block builder must consume them in a loop, both at construction time
        // and after an effectful bind.
        TrampolineScenario("long chains of already-Done binds") { n ->
            val pureOnly =
                program {
                    var count = 0
                    repeat(n) {
                        count += Program.Done(1).bind()
                    }
                    count
                }
            assertEquals(n, pureOnly.runOrThrow())

            val afterEffect =
                program {
                    perform(Increment).bind()
                    var count = 0
                    repeat(n) {
                        count += Program.Done(1).bind()
                    }
                    count
                }
            assertEquals(n, afterEffect.handleIncrements().runOrThrow())
        },
        // 9. Binds on a deferred head queue on its pending pipeline instead of nesting a
        // closure per bind; the queue is immutable, so a replay must match.
        TrampolineScenario("long flatMap chains onto a program{} head") { n ->
            var prog: Program<Int> = program { 0 }
            repeat(n) {
                prog = prog.flatMap { count -> Program.Done(count + 1) }
            }
            assertEquals(n, prog.runOrThrow())
            assertEquals(n, prog.runOrThrow())
        },
        // 10.
        TrampolineScenario("long map chains onto a program{} head") { n ->
            var prog: Program<Int> = program { 0 }
            repeat(n) {
                prog = prog.map { it + 1 }
            }
            assertEquals(n, prog.runOrThrow())
        },
        // 11. Every applied handler returns a deferred program, so this is scenario 9 reached
        // through the public handler API.
        TrampolineScenario("long flatMap chains onto an already-handled head") { n ->
            var prog: Program<Int> =
                perform(Increment)
                    .map { 0 }
                    .interpret<Counter<*>, Int> { op, resume ->
                        when (op) {
                            is Increment -> resume(Unit)
                        }
                    }
            repeat(n) {
                prog = prog.flatMap { count -> Program.Done(count + 1) }
            }
            assertEquals(n, prog.runOrThrow())
        },
        // 12. The documented way to post-process a continuation's result: resume from a
        // deferred context. Every effect then goes through a program{} thunk that binds the
        // rest of the interpretation, a deferred program that must not be forced in place.
        TrampolineScenario("interpret with resume bound inside a program{} block") { n ->
            val result =
                countIncrements(n)
                    .interpret<Counter<*>, Int> { op, resume ->
                        when (op) {
                            is Increment -> program { resume(Unit).bind() }
                        }
                    }.runOrThrow()
            assertEquals(n, result)
        },
        // 13. n single-option chooses: collectAll resumes each continuation from a program{}
        // block, so these are n deferred-context resumes in a row. Hand-built, not a block,
        // since collectAll is multi-shot.
        TrampolineScenario("sequential nondeterminism through collectAll") { n ->
            var prog: Program<Int> = Program.Done(0)
            repeat(n) {
                prog = prog.flatMap { count -> choose(listOf(1)).map { count + it } }
            }
            assertEquals(listOf(n), prog.collectAll().runOrThrow())
        },
        // 14. flatMap into a deferred head, map after, and no effect ever performed: the step
        // bound on the head returns the next level's deferred program, which must be queued
        // rather than forced.
        TrampolineScenario("effect-free non-tail bind recursion") { n ->
            fun rec(k: Int): Program<Int> =
                if (k == 0) {
                    Program.Done(0)
                } else {
                    program { 0 }.flatMap { rec(k - 1) }.map { it + 1 }
                }
            assertEquals(n, rec(n).runOrThrow())
        },
        // 15. Each level binds the next level's block and does work after it, so n deferred
        // programs are nested with no effect in between.
        TrampolineScenario("nested program{} recursion without an effect") { n ->
            fun rec(k: Int): Program<Int> =
                if (k == 0) {
                    Program.Done(0)
                } else {
                    program { rec(k - 1).bind() + 1 }
                }
            assertEquals(n, rec(n).runOrThrow())
        },
        // 16. program{} recursion, then perform: all n levels are entered before the first
        // effect, and the effect-driven unwind then runs under the handler's trampoline.
        TrampolineScenario("recursive bind before the effect") { n ->
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
            assertEquals(n, rec(n).handleIncrements().runOrThrow())
        },
    )
