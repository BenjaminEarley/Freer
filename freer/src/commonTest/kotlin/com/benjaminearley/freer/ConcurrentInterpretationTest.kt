package com.benjaminearley.freer

import com.benjaminearley.freer.effects.Generator
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.generator
import com.benjaminearley.freer.effects.io
import com.benjaminearley.freer.effects.performIO
import com.benjaminearley.freer.effects.yieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * The thread-safety contract of [Program], exercised: one Program value, built once, is
 * interpreted from many coroutines on Dispatchers.Default at the same time, and every
 * interpretation must produce the value a single one does. On the JVM and the native
 * targets the dispatcher runs the coroutines on a pool of threads; on wasmJs it has one
 * thread, so there this checks the values only. The JCStress tests in jcstress/ put the
 * same sharing under the JVM memory-model stress harness.
 */
class ConcurrentInterpretationTest {
    // Eight counter reads and one environment read in one block: 28 plus the environment.
    private val block: Program<Int> =
        program {
            var total = 0
            repeat(8) { total += fresh().bind() }
            total + perform(AskInt).bind()
        }

    // The block bound inside another block that also writes a log line, with every handler
    // applied: the writer's list state, the counter's state and the environment.
    private val handled: Program<Pair<Int, List<String>>> =
        program {
            val total = block.bind()
            tell("total $total").bind()
            total
        }.runWriter().runFresh(0).env(100)

    // The block behind a middleware that doubles the environment on its way through.
    private val intercepted: Program<Int> =
        block.intercept<Ask<*>, Int> { _, proceed -> (proceed() as Int) * 2 }.runFresh(0).env(100)

    // Three branches, each a block of its own, collected under one counter the branches share.
    private val search: Program<List<Int>> =
        choose(listOf(1, 2, 3))
            .flatMap { x -> program { x * fresh().bind() + perform(AskInt).bind() } }
            .collectAll()
            .runFresh(10)
            .env(100)

    // A generator of 1, 2 and 3 built from flatMap alone, so that its continuations are
    // multi-shot, paused at its first yield.
    private val firstYield: Generator.Yielded<Int, Int> =
        assertIs(
            yieldValue(1).flatMap { yieldValue(2) }.flatMap { yieldValue(3) }.map { 7 }.generator<Int, Int>().runOrThrow(),
        )

    // Counter reads between thunks that suspend, so that one interpretation under io() may
    // move between the dispatcher's threads.
    private val withIo: Program<Int> =
        program {
            var total = 0
            repeat(4) {
                total += fresh().bind()
                total += performIO { yield(); 10 }.bind()
            }
            total
        }.runFresh(0)

    @Test
    fun aHandledProgramGivesEveryCoroutineTheSameValue() =
        stress { assertEquals(128 to listOf("total 128"), handled.runOrThrow()) }

    @Test
    fun oneBlockUnderEachCoroutinesOwnHandlers() =
        stress { worker -> assertEquals(28 + worker, block.runFresh(0).env(worker).runOrThrow()) }

    @Test
    fun aMiddlewareParksItsCoroutinePerInterpretation() =
        stress { assertEquals(228, intercepted.runOrThrow()) }

    @Test
    fun aMultiShotSearchReplaysItsContinuationPerInterpretation() =
        stress { assertEquals(listOf(110, 122, 136), search.runOrThrow()) }

    @Test
    fun aMultiShotContinuationMayBeResumedFromEveryCoroutine() =
        stress {
            val step = assertIs<Generator.Yielded<Int, Int>>(firstYield.resume().runOrThrow())
            assertEquals(2, step.value)
        }

    // Every thunk goes through the dispatcher, which on wasmJs is the event loop, so a tenth
    // of the rounds keeps this short there.
    @Test
    fun anInterpretationUnderIoMayMoveBetweenThreads() =
        stress(rounds = ROUNDS / 10) { assertEquals(46, withIo.io()) }

    // Runs body in WORKERS coroutines on Dispatchers.Default, rounds times each, and fails
    // on the first assertion any of them fails.
    private fun stress(
        rounds: Int = ROUNDS,
        body: suspend (worker: Int) -> Unit,
    ) = runTest(timeout = 10.minutes) {
        withContext(Dispatchers.Default) {
            repeat(WORKERS) { worker ->
                launch { repeat(rounds) { body(worker) } }
            }
        }
    }

    private fun <A> Program<A>.env(value: Int): Program<A> = handle<Ask<*>, A> { value }

    private companion object {
        const val WORKERS = 32
        const val ROUNDS = 1_000
    }
}
