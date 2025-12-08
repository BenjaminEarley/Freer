@file:Suppress("UNCHECKED_CAST")

/**
 * Freer Monads & Extensible Effects.
 * A Kotlin implementation based on "Freer Monads, More Extensible Effects" by Kiselyov & Ishii.
 * https://okmij.org/ftp/Haskell/extensible/more.pdf
 */

typealias Erased = Any? // The "Existential" type. We use Any? because of type erasure.

// interpret effects
inline fun <reified E : Effect<*>, A, B> Program<A>.interpret(
    noinline transformDone: (A) -> Program<B>,
    noinline rule: (E, (Erased) -> Program<B>) -> Program<B>,
): Program<B> = interpreterLoop(this, E::class.java, transformDone, rule)

inline fun <reified E : Effect<*>, A> Program<A>.interpret(noinline rule: (E, (Erased) -> Program<A>) -> Program<A>): Program<A> =
    interpret(
        transformDone = { Program.Done(it) },
        rule = rule,
    )

// Execute the final program
fun <A> runInterpreter(program: Program<Result<A>>) {
    when (program) {
        is Program.Done -> {
            program.value.fold(
                onSuccess = { println(">> Final Result: $it") },
                onFailure = { println(">> Pipeline Failed: ${it.message}") },
            )
        }

        is Program.Suspended<*, *> -> {
            println("!! Logic Error: Unhandled effects remain !!")
        }
    }
}

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
interface Effect<out R>

sealed class Program<out A> {
    // Pure: The program is finished with a final value
    data class Done<out A>(
        val value: A,
    ) : Program<A>()

    // Impure: The program is paused (Suspended), waiting for a Request to be handled
    class Suspended<Response, out A>(
        val effect: Effect<Response>,
        val pipeline: Pipeline<Response, A>, // The continuation logic
    ) : Program<A>()
}

fun <A, B> Program<A>.flatMap(f: (A) -> Program<B>): Program<B> =
    when (this) {
        is Program.Done -> {
            f(this.value)
        }

        is Program.Suspended<*, *> -> {
            val suspended = this as Program.Suspended<Erased, A>
            Program.Suspended(suspended.effect, suspended.pipeline.then(f))
        }
    }

fun <A, B> Program<A>.map(f: (A) -> B): Program<B> = flatMap { Program.Done(f(it)) }

// Helper to perform an effect
fun <R> perform(effect: Effect<R>): Program<R> = Program.Suspended(effect, Pipeline.Step { Program.Done(it) })

// The Virtual Machine
// This function advances the pipeline by one step.
tailrec fun <A, B> resume(
    pipeline: Pipeline<A, B>,
    input: A,
): Program<B> =
    when (pipeline) {
        is Pipeline.Step -> {
            pipeline.fn(input)
        }

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
                        Program.Suspended(suspended.effect, newQueue) as Program<B>
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
fun <Target : Effect<*>, A, B> interpreterLoop(
    program: Program<A>,
    targetClass: Class<Target>,
    transformDone: (A) -> Program<B>, // Logic for "Pure" values (A -> B)
    rule: (Target, (Erased) -> Program<B>) -> Program<B>, // Logic for "Impure" effects
): Program<B> =
    when (program) {
        is Program.Done -> {
            transformDone(program.value)
        }

        is Program.Suspended<*, *> -> {
            val suspended = program as Program.Suspended<Erased, A>
            val effect = suspended.effect
            val pipeline = suspended.pipeline

            if (targetClass.isInstance(effect)) {
                // MATCH: We found the effect we are looking for.
                // We pass a 'resume' function that automatically recurses.
                rule(effect as Target) { response ->
                    // 1. Resume the pipeline (gets us the next step as Program<A>)
                    // 2. Recurse (converts that Program<A> -> Program<B>)
                    interpreterLoop(resume(pipeline, response), targetClass, transformDone, rule)
                }
            } else {
                // NO MATCH: Relay the effect.
                // We construct a new step that waits for the result, then recurses.
                val forwardedPipeline =
                    Pipeline.Step { response: Erased ->
                        interpreterLoop(resume(pipeline, response), targetClass, transformDone, rule)
                    }
                Program.Suspended(effect, forwardedPipeline)
            }
        }
    }
