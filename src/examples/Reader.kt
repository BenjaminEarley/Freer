/*
 * A Reader effect: ask for a value from the environment.
 *
 * local() overrides the environment for one sub-program without consuming the
 * effect: it asks downstream once for the current value, then an intercept answers
 * Ask with the transformed value instead of proceeding downstream, so everything
 * outside the override still gets the downstream answer. Both steps are plain
 * flatMap and a rule that never suspends, so local() also works under a
 * multi-shot handler such as collectAll.
 */
package ffree.examples

import ffree.Effect
import ffree.Program
import ffree.flatMap
import ffree.handle
import ffree.intercept
import ffree.perform
import ffree.program
import ffree.runOrThrow

sealed interface Reader<out R> : Effect<R>

data object AskEnv : Reader<String>

fun ask(): Program<String> = perform(AskEnv)

fun <A> Program<A>.runReader(env: String): Program<A> =
    handle<Reader<*>, A> { op ->
        when (op) {
            is AskEnv -> env
        }
    }

/** Run this sub-program with the environment transformed; the override ends with it. */
fun <A> Program<A>.local(transform: (String) -> String): Program<A> =
    ask().flatMap { e ->
        val env = transform(e)
        this@local.intercept<Reader<*>, A> { op, _ ->
            when (op) {
                is AskEnv -> env
            }
        }
    }

fun readerExample() {
    val greet: Program<String> = program { "Hello, ${ask().bind()}!" }

    println("plain           = ${greet.runReader("world").runOrThrow()}")

    val combined =
        program {
            val inner = greet.local { it.uppercase() }.bind()
            val outer = greet.bind()
            "$inner / $outer"
        }.runReader("world").runOrThrow()
    println("local override  = $combined")
}
