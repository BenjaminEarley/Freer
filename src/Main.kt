@file:Suppress("UNCHECKED_CAST")

/**
 * Freer Monads & Extensible Effects.
 * A Kotlin implementation based on "Freer Monads, More Extensible Effects" by Kiselyov & Ishii.
 * https://okmij.org/ftp/Haskell/extensible/more.pdf
 */

typealias Erased = Any? // The "Existential" type. We use Any? because of type erasure.

// The "Fast Type-Aligned Queue"
// https://okmij.org/ftp/Haskell/Reflection.html
// This represents the "Pipeline" of functions waiting to be executed.
sealed class Pipeline<in Input, out Output> {
    // A single function step
    class Step<Input, Output>(
        val fn: (Input) -> Program<Output>,
    ) : Pipeline<Input, Output>()

    // Concatenation of two pipelines
    class Join(
        val left: Pipeline<Erased, Erased>,
        val right: Pipeline<Erased, Erased>,
    ) : Pipeline<Erased, Erased>()

    // O(1) Append: Adds a step to the end of the pipeline
    fun <NewOutput> then(fn: (Output) -> Program<NewOutput>): Pipeline<Input, NewOutput> {
        val nextStep = Step(fn) as Pipeline<Erased, Erased>
        val current = this as Pipeline<Erased, Erased>
        return Join(current, nextStep) as Pipeline<Input, NewOutput>
    }
}

// A Request that expects a return value of type R
// This is the open union of effects
interface Request<out R>

sealed class Program<out A> {
    // Pure: The program is finished with a final value
    data class Done<out A>(
        val value: A,
    ) : Program<A>()

    // Impure: The program is paused (Suspended), waiting for a Request to be handled
    class Suspended<Response, out A>(
        val request: Request<Response>,
        val pipeline: Pipeline<Response, A>, // The continuation logic
    ) : Program<A>()
}

fun <A, B> Program<A>.flatMap(f: (A) -> Program<B>): Program<B> =
    when (this) {
        is Program.Done -> f(this.value)
        is Program.Suspended<*, *> -> {
            val suspended = this as Program.Suspended<Erased, A>
            Program.Suspended(suspended.request, suspended.pipeline.then(f))
        }
    }

fun <A, B> Program<A>.map(f: (A) -> B): Program<B> = flatMap { Program.Done(f(it)) }

// Helper to perform a request
fun <R> perform(request: Request<R>): Program<R> = Program.Suspended(request, Pipeline.Step { Program.Done(it) })

// The Virtual Machine
// This function advances the pipeline by one step.
tailrec fun <A, B> resume(
    pipeline: Pipeline<A, B>,
    input: A,
): Program<B> =
    when (pipeline) {
        is Pipeline.Step -> pipeline.fn(input)
        is Pipeline.Join -> {
            val left = pipeline.left
            val right = pipeline.right

            if (left is Pipeline.Step) {
                // Left is a single step: Run it.
                val leftStep = left as Pipeline.Step<A, Erased>

                when (val result = leftStep.fn(input)) {
                    is Program.Done -> {
                        // Step finished cleanly: Feed result into the Right side
                        resume(right as Pipeline<Erased, B>, result.value)
                    }
                    is Program.Suspended<*, *> -> {
                        // Step suspended: We must attach the Right side to the new suspension
                        val suspended = result as Program.Suspended<Erased, Erased>

                        // We extend the *new* suspension's pipeline with our current 'right'
                        val newQueue =
                            suspended.pipeline.then { y ->
                                resume(right as Pipeline<Erased, B>, y)
                            }
                        Program.Suspended(suspended.request, newQueue) as Program<B>
                    }
                }
            } else {
                // Left is a Join: Rotate Right to maintain performance guarantees.
                // ( (A + B) + C ) -> ( A + (B + C) )
                val leftJoin = left as Pipeline.Join
                val newLeft = leftJoin.left
                val newRight = Pipeline.Join(leftJoin.right, right)
                resume(Pipeline.Join(newLeft, newRight) as Pipeline<A, B>, input)
            }
        }
    }

// Generic Interpreter Loop
fun <Target : Request<*>, A> interpreterLoop(
    program: Program<A>,
    targetClass: Class<Target>,
    rule: (Target, (Erased) -> Program<A>) -> Program<A>,
): Program<A> =
    when (program) {
        is Program.Done -> program
        is Program.Suspended<*, *> -> {
            val suspended = program as Program.Suspended<Erased, A>
            val request = suspended.request
            val pipeline = suspended.pipeline

            if (targetClass.isInstance(request)) {
                // MATCH: Found the request we are looking for.
                rule(request as Target) { response ->
                    // Resume the pipeline with the response, then recurse
                    interpreterLoop(resume(pipeline, response), targetClass, rule)
                }
            } else {
                // NO MATCH: Relay the request.
                // We construct a NEW step that catches the result and recurses.
                val forwardedPipeline =
                    Pipeline.Step { response: Erased ->
                        interpreterLoop(resume(pipeline, response), targetClass, rule)
                    }
                Program.Suspended(request, forwardedPipeline)
            }
        }
    }

// Public Entry Point for Interpreters
inline fun <reified E : Request<*>, A> Program<A>.interpret(noinline rule: (E, (Erased) -> Program<A>) -> Program<A>): Program<A> =
    interpreterLoop(this, E::class.java, rule)

// ==========================
// DOMAIN SPECIFIC LOGIC
// ==========================

// --- Console Requests ---
sealed interface Console<out R> : Request<R>

data class PrintLine(
    val msg: String,
) : Console<Unit>

object ReadLine : Console<String>

fun printLine(msg: String) = perform(PrintLine(msg))

fun readLine() = perform(ReadLine)

// --- Memory Requests ---
sealed interface Memory<out R> : Request<R>

data class Memorize(
    val value: Int,
) : Memory<Unit> // Put

object Recall : Memory<Int> // Get

fun memorize(value: Int) = perform(Memorize(value))

fun recall() = perform(Recall)

// Run Memory requests
fun <A> Program<A>.runMemory(initialState: Int): Program<A> {
    // Helper loop to carry the state 's'
    fun loop(
        prog: Program<A>,
        s: Int,
    ): Program<A> =
        when (prog) {
            is Program.Done -> prog
            is Program.Suspended<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val suspended = prog as Program.Suspended<Erased, A>
                val op = suspended.request
                val q = suspended.pipeline

                if (op is Memory) {
                    when (op) {
                        // State Logic: Resume pipeline with 's', pass 's' forward
                        is Recall -> loop(resume(q, s), s)
                        // State Logic: Resume pipeline with 'Unit', pass 'op.value' forward
                        is Memorize -> loop(resume(q, Unit), op.value)
                    }
                } else {
                    // Relay Logic: Capture flow, execute unknown effect, then recurse
                    val relayQ =
                        Pipeline.Step { y: Erased ->
                            loop(resume(q, y), s)
                        }
                    Program.Suspended(op, relayQ)
                }
            }
        }
    return loop(this, initialState)
}

// Run Console requests
fun <A> Program<A>.runConsole(): Program<A> =
    interpret<Console<*>, A> { op, resume ->
        when (op) {
            is PrintLine -> {
                println("[Console] ${op.msg}")
                resume(Unit)
            }
            is ReadLine -> {
                val input = "dummy user input"
                println("[Console] (Reading input...) -> '$input'")
                resume(input)
            }
        }
    }

// --- Main Program ---

fun countdown(): Program<Unit> =
    recall().flatMap { n ->
        if (n <= 0) {
            printLine("Liftoff!")
        } else {
            printLine("T-minus $n...")
                .flatMap { memorize(n - 1) }
                .flatMap { countdown() }
        }
    }

fun main() {
    println("--- Building Program ---")
    val program = countdown()

    println("\n--- Interpreting: Memory(3) -> Console ---")

    // 1. Handle Memory requests first.
    //    This returns a Program that still contains Console requests.
    val memoryHandled = program.runMemory(initialState = 3)

    // 2. Handle Console requests.
    //    This returns a Program.Done (if all effects are handled).
    val fullyHandled = memoryHandled.runConsole()

    if (fullyHandled is Program.Done) {
        println("\n--- Finished with result: ${fullyHandled.value} ---")
    }
}
