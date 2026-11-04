@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.perform

/**
 * Generator output: [yieldValue] performs a [Yield], and the [generator] handler reifies
 * the continuation as data. The program becomes a [Generator] value: [Generator.Finished]
 * with the result, or [Generator.Yielded] holding the value and the rest of the program
 * as a callable continuation. The driver decides when, and whether, to resume; in between,
 * the suspended program is just data:
 *
 * ```
 * var step = countdown.generator<Int, String>().runOrThrow()
 * while (step is Generator.Yielded) {
 *     println(step.value)
 *     step = step.resume().runOrThrow()
 * }
 * ```
 */
public sealed interface Emit<out R> : Effect<R> {
    /** Hand [value] to the driver and pause until it resumes the generator. */
    public class Yield<out T> internal constructor(
        public val value: T,
    ) : Emit<Unit> {
        override fun toString(): String = "Yield($value)"
    }
}

/** Yield [value] to the driver of the enclosing [generator] and pause until resumed. */
public fun <T> yieldValue(value: T): Program<Unit> = perform(Emit.Yield(value))

/** One step of a generator run by [generator]: either [Finished] or [Yielded]. */
public sealed class Generator<out T, out A> {
    /** The generator returned [result]; there is nothing left to resume. */
    public class Finished<out A> internal constructor(
        public val result: A,
    ) : Generator<Nothing, A>() {
        override fun toString(): String = "Finished($result)"
    }

    /**
     * The generator yielded [value]. [resume] returns the program for the next step;
     * the driver decides when, and whether, to run it.
     */
    public class Yielded<out T, out A> internal constructor(
        public val value: T,
        public val resume: () -> Program<Generator<T, A>>,
    ) : Generator<T, A>() {
        override fun toString(): String = "Yielded($value)"
    }
}

/**
 * Run this program as a generator: every [Emit.Yield] pauses it as a [Generator.Yielded]
 * holding the rest of the program, and completion gives [Generator.Finished]. The
 * yielded type is erased, so one generator() should see one yielded type [T].
 */
public fun <T, A> Program<A>.generator(): Program<Generator<T, A>> =
    interpret<Emit<*>, A, Generator<T, A>>(
        transformDone = { a -> Program.Done(Generator.Finished(a)) },
        rule = { op, resume ->
            // The continuation is captured and invoked after the rule has returned — a
            // deferred resume, so it produces a real Program each time it's called.
            when (op) {
                is Emit.Yield<*> -> Program.Done(Generator.Yielded(op.value as T) { resume(Unit) })
            }
        },
    )
