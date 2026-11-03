/*
 * Effects the golden examples need beyond the built-in ones, each with a handler written
 * against the public API the way a user of the library would write it.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer

/** Append [line] to a log. */
data class Tell(
    val line: String,
) : Effect<Unit>

fun tell(line: String): Program<Unit> = perform(Tell(line))

/**
 * Collect every told line next to the final value, in program order. The log is handler
 * state, which the interpreter captures at every effect it passes on, so a continuation that
 * an outer handler resumes more than once extends the log as it was when the continuation
 * was captured: under nondeterminism each branch has its own log, sharing the lines told
 * before the choice.
 */
fun <A> Program<A>.runWriter(): Program<Pair<A, List<String>>> =
    interpretS<Tell, List<String>, A, Pair<A, List<String>>>(
        initialState = emptyList(),
        transformDone = { lines, value -> Program.Done(value to lines) },
        rule = { lines, op, resume -> resume(lines + op.line, Unit) },
    )

/** Abort with [error]; never answered, so the program does not continue past it. */
data class Throw<out E>(
    val error: E,
) : Effect<Nothing>

fun <E> throwError(error: E): Program<Nothing> = perform(Throw(error))

/** Handle [Throw] as an [Either]: the final value on the right, or the error on the left. */
fun <E, A> Program<A>.runError(): Program<Either<E, A>> =
    interpret<Throw<*>, A, Either<E, A>>(
        transformDone = { value -> Program.Done(Right(value)) },
        rule = { op, _ -> Program.Done(Left(op.error as E)) },
    )

/** Replace the rest of a program that threw with [handler] applied to the error. */
fun <E, A> Program<A>.catchError(handler: (E) -> Program<A>): Program<A> =
    interpret<Throw<*>, A> { op, _ -> handler(op.error as E) }

/** Read a second environment, independent of the built-in Reader. */
data object AskDouble : Effect<Double>

fun askDouble(): Program<Double> = perform(AskDouble)

fun <A> Program<A>.runReaderDouble(env: Double): Program<A> = interpret<AskDouble, A> { _, resume -> resume(env) }

/** Answer the [AskDouble]s inside this program with the environment transformed once, on entry. */
fun <A> Program<A>.localDouble(transform: (Double) -> Double): Program<A> =
    askDouble().flatMap { outer ->
        val env = transform(outer)
        this@localDouble.interpret<AskDouble, A> { _, resume -> resume(env) }
    }

/** Request the next unused integer. */
data object Fresh : Effect<Int>

fun fresh(): Program<Int> = perform(Fresh)

/** Answer each [Fresh] with a counter that starts at [start]. */
fun <A> Program<A>.runFresh(start: Int): Program<A> =
    interpretS<Fresh, Int, A>(start) { next, _, resume -> resume(next + 1, next) }
