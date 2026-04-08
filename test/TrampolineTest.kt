/**
 * Trampoline stack-safety test.
 *
 * Tests three scenarios:
 * 1. interpret (low-level) — trampoline via direct resume
 * 2. handle (DSL) — trampoline via the Done-path optimization in ProgramDsl.kt
 * 3. handle + forwarding — handler that forwards effects past a non-matching handler
 */

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

        assert(count == N) { "Expected $N, got $count" }
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
