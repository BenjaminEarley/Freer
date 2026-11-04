/*
 * The cost of one effect, in time and in bytes allocated, through every handler style, next
 * to a raw suspend loop that parks and resumes the same number of times. Each invocation
 * runs EFFECTS effects, and the scores are per effect.
 */
package com.benjaminearley.freer.benchmark

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.handleS
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.interpretS
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup

const val EFFECTS = 1_000

sealed interface Tick<out R> : Effect<R>

data object Inc : Tick<Unit>

sealed interface Unrelated<out R> : Effect<R>

data object Noop : Unrelated<Unit>

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@OperationsPerInvocation(EFFECTS)
class EffectBenchmark {
    // One program{} block performing EFFECTS increments and counting them.
    private fun countIncrements(): Program<Int> =
        program {
            var count = 0
            repeat(EFFECTS) {
                perform(Inc).bind()
                count++
            }
            count
        }

    @Benchmark
    fun dslHandle(): Int = countIncrements().handle<Tick<*>, Int> { Unit }.runOrThrow()

    @Benchmark
    fun dslInterpretDirect(): Int = countIncrements().interpret<Tick<*>, Int> { _, resume -> resume(Unit) }.runOrThrow()

    @Benchmark
    fun dslHandleS(): Int = countIncrements().handleS<Tick<*>, Int, Int>(0) { count, _ -> (count + 1) to Unit }.runOrThrow()

    @Benchmark
    fun dslInterpretS(): Int =
        countIncrements().interpretS<Tick<*>, Int, Int>(0) { count, _, resume -> resume(count + 1, Unit) }.runOrThrow()

    @Benchmark
    fun dslInterceptThenHandle(): Int =
        countIncrements()
            .intercept<Tick<*>, Int> { _, proceed -> proceed() }
            .handle<Tick<*>, Int> { Unit }
            .runOrThrow()

    // The increments pass through a handler of another family before reaching theirs.
    @Benchmark
    fun dslHandleThroughRelay(): Int =
        countIncrements()
            .handle<Unrelated<*>, Int> { Unit }
            .handle<Tick<*>, Int> { Unit }
            .runOrThrow()

    // The same work as a hand-built chain of flatMaps instead of a block.
    @Benchmark
    fun flatMapChainInterpretDirect(): Int {
        fun build(
            remaining: Int,
            count: Int,
        ): Program<Int> = if (remaining == 0) Program.Done(count) else perform(Inc).flatMap { build(remaining - 1, count + 1) }
        return build(EFFECTS, 0).interpret<Tick<*>, Int> { _, resume -> resume(Unit) }.runOrThrow()
    }

    // A suspend block that parks EFFECTS times, resumed by a driver loop: what a reified
    // suspension costs with nothing on top of the compiler's own state machine.
    private class Park {
        var pending: Continuation<Unit>? = null

        suspend fun park(): Unit =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                pending = continuation
                COROUTINE_SUSPENDED
            }
    }

    @Benchmark
    fun rawSuspendLoop(): Int {
        val park = Park()
        var result: Int? = null
        val body: suspend () -> Int = {
            var count = 0
            repeat(EFFECTS) {
                park.park()
                count++
            }
            count
        }
        body.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
        while (result == null) {
            val continuation = checkNotNull(park.pending)
            park.pending = null
            continuation.resume(Unit)
        }
        return checkNotNull(result)
    }
}
