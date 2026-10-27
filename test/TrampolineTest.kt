/**
 * Trampoline stack-safety tests. Each scenario runs N = 100,000 effects (or binds)
 * through a differently shaped program/handler stack that could overflow the JVM
 * stack: direct-resume trampolining, the handle DSL's Done-path optimization,
 * effect forwarding past a non-matching handler, stateful interpretation, deferred
 * resumes via intercept, left-associated bind chains, deep non-tail recursion,
 * long chains of pure binds, bind chains queued on a deferred head, resume bound
 * inside a program{} block (deferred-context resume), sequential nondeterminism
 * through collectAll, effect-free non-tail bind recursion, nested program{}
 * recursion that performs no effect, and recursion that binds before performing
 * an effect.
 */

import ffree.Effect
import ffree.Program
import ffree.examples.choose
import ffree.examples.collectAll
import ffree.flatMap
import ffree.handle
import ffree.intercept
import ffree.interpret
import ffree.interpretS
import ffree.map
import ffree.perform
import ffree.program
import ffree.runOrThrow

sealed interface Counter<out R> : Effect<R>

data object Increment : Counter<Unit>

// An unrelated effect that forces forwarding
sealed interface Unrelated<out R> : Effect<R>

data object Noop : Unrelated<Unit>

const val N = 100_000 // Well beyond the ~5-10K stack limit

fun main() {
    println("=== Trampoline Stack-Safety Tests ===")
    println("Each test runs $N effects.")
    println()

    test("1. interpret (low-level, direct resume)") {
        // Build a program that performs N Increment effects using program { }
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Handle with raw interpret — resume called directly → trampoline
        prog
            .interpret<Counter<*>, Int> { op, resume ->
                when (op) {
                    is Increment -> resume(Unit)
                }
            }.runOrThrow()
    }

    test("2. handle (DSL, Done-path optimization)") {
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Handle with DSL — program { rule(op) } returns Done → resume called directly
        prog
            .handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("3. handle + forwarding (effect passes through a non-matching handler)") {
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Increment effects are forwarded past the Unrelated handler, then caught by Counter handler.
        // Each forwarded effect creates a Pipeline.Step with a deferred interpreterLoop call.
        // The outer handler's trampoline keeps the stack flat.
        prog
            .handle<Unrelated<*>, Int> { op ->
                when (op) {
                    is Noop -> Unit
                }
            }.handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("4. interpretS (stateful handler, extract final state)") {
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

        // check, not assert: JVM assertions are disabled unless -ea is passed
        check(count == N) { "Expected $N, got $count" }
        count
    }

    test("5. intercept + handle (middleware chain, deferred resume)") {
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Middleware intercepts every Increment (re-emits via proceed), then handle consumes it.
        // proceed() calls perform(op) → Suspended path → deferred resume.
        // Stack safety comes from the deferred path resetting the stack.
        prog
            .intercept<Counter<*>, Int> { op, proceed ->
                when (op) {
                    is Increment -> proceed()
                }
            }.handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("6. left-associated flatMap chain + handle (pathological case)") {
        // Build a deeply left-associated chain — the worst case for both
        // the type-aligned queue AND the trampoline.
        var prog: Program<Int> = Program.Done(0)
        for (i in 1..N) {
            prog =
                prog.flatMap { count ->
                    perform(Increment).map { count + 1 }
                }
        }

        prog
            .handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("7. deep non-tail recursion (work after a suspending bind)") {
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
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("8. long chains of pure (already-Done) binds") {
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
        val constructed = pureOnly.runOrThrow()

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

        check(constructed == N && interpreted == N) { "Expected $N/$N, got $constructed/$interpreted" }
        interpreted
    }

    test("9. long flatMap chains onto a program{} head (Defer pending queue)") {
        // A Defer head (program{}, or any handled program) used to nest one
        // closure per bind, overflowing around depth ~10-20K when forced. Binds
        // now queue on the Defer's pending pipeline instead.
        var prog: Program<Int> = program { 0 }
        repeat(N) {
            prog = prog.flatMap { count -> Program.Done(count + 1) }
        }
        val first = prog.runOrThrow()
        val replayed = prog.runOrThrow() // pending queue is immutable — replay must match
        check(first == N && replayed == N) { "Expected $N/$N, got $first/$replayed" }
        first
    }

    test("10. long map chains onto a program{} head") {
        var prog: Program<Int> = program { 0 }
        repeat(N) {
            prog = prog.map { it + 1 }
        }
        val result = prog.runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("11. long flatMap chains onto an already-handled head") {
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
        val result = prog.runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("12. interpret with resume bound inside program{} (deferred-context resume)") {
        // The documented way to post-process a continuation's result: call resume
        // from a deferred context. Every effect then goes through a program{} thunk
        // that binds the rest of the interpretation — a deferred program that must
        // not be forced in place, or each effect nests a frame.
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        val result =
            prog
                .interpret<Counter<*>, Int> { op, resume ->
                    when (op) {
                        is Increment -> program { resume(Unit).bind() }
                    }
                }.runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("13. sequential nondeterminism depth (N single-option chooses through collectAll)") {
        // collectAll resumes each continuation from a program{} block, so N chained
        // choices are N deferred-context resumes in a row. A hand-built chain, not
        // a program{} block: collectAll is multi-shot.
        var prog: Program<Int> = Program.Done(0)
        repeat(N) {
            prog = prog.flatMap { count -> choose(listOf(1)).map { count + it } }
        }
        val result = prog.collectAll().runOrThrow()
        check(result == listOf(N)) { "Expected [$N], got $result" }
        result.single()
    }

    test("14. effect-free non-tail bind recursion (flatMap into a deferred head, map after)") {
        // No effect is ever performed, so no interpreter loop is involved: the
        // step bound on the program{} head returns the next level's deferred
        // program, which must be queued rather than forced.
        fun rec(n: Int): Program<Int> =
            if (n == 0) {
                Program.Done(0)
            } else {
                program { 0 }.flatMap { rec(n - 1) }.map { it + 1 }
            }

        val result = rec(N).runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("15. nested program{} recursion without an effect") {
        // Each level binds the next level's program{} block and does work after
        // it, so N deferred programs are nested with no effect in between.
        fun rec(k: Int): Program<Int> =
            if (k == 0) {
                Program.Done(0)
            } else {
                program { rec(k - 1).bind() + 1 }
            }

        val result = rec(N).runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("16. recursive bind before the effect (program{} recursion, then perform)") {
        // The recursive bind comes first, so all N levels are entered before the
        // first effect is performed; the effect-driven unwind then runs under the
        // handler's trampoline.
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
        check(result == N) { "Expected $N, got $result" }
        result
    }

    println()
    println("All tests passed. No StackOverflowError.")
}

private fun test(
    name: String,
    block: () -> Any?,
) {
    print("  $name ... ")
    try {
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        println("OK  (${"%,.0f".format(elapsed)} ms, result=$result)")
    } catch (e: StackOverflowError) {
        println("FAILED — StackOverflowError!")
        throw e
    }
}
