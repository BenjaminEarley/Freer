/*
 * Three encodings of one stream consumer: n ints fed one at a time into a left-associated
 * chain of n binds that sums them.
 *
 * The naive iteratee stores its continuation as a composed closure. Its bind re-wraps that
 * closure, so each element fed re-enters every pending wrapper and re-nests the rest on the
 * way out: quadratic overall. Storing the pending continuations as a concrete sequence makes
 * the run linear. Program does this with its queue while remaining a replayable value that
 * handlers can match on; Kotlin suspend gets there too, as the compiler keeps the continuation
 * as a linked chain of frames consumed once, but the result is one-shot and opaque.
 */
package com.benjaminearley.freer.benchmark

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.interpretS
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform
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
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup

// Iteratee whose continuation is a plain function.
private sealed class NaiveIt<out A> {
    class Pure<out A>(
        val value: A,
    ) : NaiveIt<A>()

    class Get<out A>(
        val cont: (Int) -> NaiveIt<A>,
    ) : NaiveIt<A>()
}

// The quadratic pattern: bind on Get re-wraps the continuation.
private fun <A, B> NaiveIt<A>.flatMap(k: (A) -> NaiveIt<B>): NaiveIt<B> =
    when (this) {
        is NaiveIt.Pure -> k(value)
        is NaiveIt.Get -> NaiveIt.Get { i -> cont(i).flatMap(k) }
    }

private val naiveGet: NaiveIt<Int> = NaiveIt.Get { i -> NaiveIt.Pure(i) }

private fun naiveAddGet(x: Int): NaiveIt<Int> = naiveGet.flatMap { i -> NaiveIt.Pure(i + x) }

private tailrec fun <A> naiveFeedAll(
    iteratee: NaiveIt<A>,
    next: Int,
): A =
    when (iteratee) {
        is NaiveIt.Pure -> iteratee.value
        is NaiveIt.Get -> naiveFeedAll(iteratee.cont(next), next + 1)
    }

sealed interface Stream<out R> : Effect<R>

data object Next : Stream<Int>

private fun addGet(x: Int): Program<Int> = perform(Next).map { i -> i + x }

// The stream request as a raw suspension: park until the driver resumes with the next int.
private class Feed {
    var pending: Continuation<Int>? = null

    suspend fun next(): Int =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            pending = continuation
            COROUTINE_SUSPENDED
        }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
class IterateeRaceBenchmark {
    @Param("2000", "4000")
    var n: Int = 0

    @Benchmark
    fun naiveIteratee(): Int {
        var acc: NaiveIt<Int> = NaiveIt.Pure(0)
        repeat(n) { acc = acc.flatMap(::naiveAddGet) }
        return naiveFeedAll(acc, 1)
    }

    @Benchmark
    fun rawSuspend(): Int {
        val feed = Feed()
        var k: suspend (Int) -> Int = { it }
        repeat(n) {
            val prev = k
            k = { x -> prev(x) + feed.next() }
        }
        var done: Int? = null
        val entry: suspend () -> Int = { k(0) }
        entry.startCoroutine(Continuation(EmptyCoroutineContext) { done = it.getOrThrow() })
        var next = 1
        while (done == null) {
            val continuation = checkNotNull(feed.pending)
            feed.pending = null
            continuation.resume(next++)
        }
        return checkNotNull(done)
    }

    @Benchmark
    fun program(): Int {
        var acc: Program<Int> = Program.Done(0)
        repeat(n) { acc = acc.flatMap(::addGet) }
        return acc
            .interpretS<Stream<*>, Int, Int>(1) { next, _, resume -> resume(next + 1, next) }
            .runOrThrow()
    }
}
