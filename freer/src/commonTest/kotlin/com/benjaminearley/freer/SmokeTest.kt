package com.benjaminearley.freer

import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.io
import com.benjaminearley.freer.effects.performIO
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

private sealed interface Greeting<out R> : Effect<R>

private data class Greet(
    val name: String,
) : Greeting<String>

// Runs a suspend block that completes without suspending and returns its result.
private fun <T> runSync(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended" }.getOrThrow()
}

class SmokeTest {
    @Test
    fun programIsInterpretedByHandle() {
        val greeting: Program<String> =
            program {
                val first = perform(Greet("Ada")).bind()
                val second = perform(Greet("Alan")).bind()
                "$first, $second"
            }

        val result =
            greeting
                .handle<Greeting<*>, String> { op ->
                    when (op) {
                        is Greet -> "Hello ${op.name}"
                    }
                }.runOrThrow()

        assertEquals("Hello Ada, Hello Alan", result)
    }

    @Test
    fun collectAllExploresEveryBranch() {
        val pairs: Program<Pair<Int, String>> =
            choose(listOf(1, 2)).flatMap { n ->
                choose(listOf("a", "b")).map { s -> n to s }
            }

        assertEquals(
            listOf(1 to "a", 1 to "b", 2 to "a", 2 to "b"),
            pairs.collectAll().runOrThrow(),
        )
    }

    @Test
    fun ioRunsSynchronousThunks() {
        val log = mutableListOf<String>()
        val total: Program<Int> =
            program {
                val a =
                    performIO {
                        log += "a"
                        20
                    }.bind()
                val b =
                    performIO {
                        log += "b"
                        22
                    }.bind()
                a + b
            }

        assertEquals(42, runSync { total.io() })
        assertEquals(listOf("a", "b"), log)
    }
}
