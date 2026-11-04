/*
 * Interpretation of long bind chains in both associations. A naive free monad interprets a
 * left-associated chain in quadratic time; the type-aligned queue makes both linear, which
 * shows as a per-element cost that stays flat when n grows tenfold. Construction is timed
 * with interpretation: the right-associated chain is built lazily, so its construction
 * necessarily happens during interpretation.
 */
package com.benjaminearley.freer.benchmark

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.interpret
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.runOrThrow
import java.util.concurrent.TimeUnit
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

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
class BindChainBenchmark {
    @Param("10000", "100000")
    var n: Int = 0

    private fun <A> Program<A>.handleIncrements(): Program<A> = interpret<Tick<*>, A> { _, resume -> resume(Unit) }

    // ((Done(0) >>= f) >>= f) >>= f ...
    @Benchmark
    fun leftAssociated(): Int {
        var program: Program<Int> = Program.Done(0)
        for (i in 1..n) {
            program = program.flatMap { count -> perform(Inc).map { count + 1 } }
        }
        return program.handleIncrements().runOrThrow()
    }

    // Done(0) >>= (f >>= (f >>= (f ...)))
    @Benchmark
    fun rightAssociated(): Int {
        fun build(
            remaining: Int,
            count: Int,
        ): Program<Int> = if (remaining == 0) Program.Done(count) else perform(Inc).flatMap { build(remaining - 1, count + 1) }
        return build(n, 0).handleIncrements().runOrThrow()
    }
}
