/*
 * Golden tests: small programs with known answers, run through the library's handlers.
 * Each program is named after the example it was ported from; the README's Semantics
 * section says where the expected values come from.
 */
package com.benjaminearley.freer

import com.benjaminearley.freer.effects.Generator
import com.benjaminearley.freer.effects.ask
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.generator
import com.benjaminearley.freer.effects.getState
import com.benjaminearley.freer.effects.guard
import com.benjaminearley.freer.effects.ifte
import com.benjaminearley.freer.effects.local
import com.benjaminearley.freer.effects.modifyState
import com.benjaminearley.freer.effects.msplit
import com.benjaminearley.freer.effects.orElse
import com.benjaminearley.freer.effects.putState
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.effects.recover
import com.benjaminearley.freer.effects.runReader
import com.benjaminearley.freer.effects.runStateWithFinal
import com.benjaminearley.freer.effects.transactional
import com.benjaminearley.freer.effects.yieldValue
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

// What a driver does with the rest of a generator before resuming it, given the value just yielded.
private typealias Wrap = (Int, Program<Generator<Int, Unit>>) -> Program<Generator<Int, Unit>>

class PaperExamplesTest {
    private fun add(
        a: Program<Int>,
        b: Program<Int>,
    ): Program<Int> = a.flatMap { x -> b.map { y -> x + y } }

    private fun addDouble(
        a: Program<Double>,
        b: Program<Double>,
    ): Program<Double> = a.flatMap { x -> b.map { y -> x + y } }

    private fun <A, B> mapM(
        items: List<A>,
        f: (A) -> Program<B>,
    ): Program<List<B>> =
        items.fold<A, Program<List<B>>>(Program.Done(emptyList())) { acc, item ->
            acc.flatMap { done -> f(item).map { done + it } }
        }

    private fun <A> mzero(): Program<A> = choose(emptyList())

    // ---- Reader

    // Adds one to the environment.
    private val t1 = add(ask(), Program.Done(1))

    @Test
    fun t1AskPlusOne() = assertEquals(11, t1.runReader(10).runOrThrow())

    // Reads two environments of different types and combines them.
    private val t2 =
        program {
            val v1 = ask<Int>().bind()
            val v2 = askDouble().bind()
            (v1 + 1).toDouble() + (v2 + 2)
        }

    @Test
    fun t2TwoEnvironmentsHandledInEitherOrder() {
        assertEquals(33.0, t2.runReader(10).runReaderDouble(20.0).runOrThrow())
        assertEquals(33.0, t2.runReaderDouble(20.0).runReader(10).runOrThrow())
    }

    // t1 plus t1 under an environment raised by ten.
    private val t3 = add(t1, t1.local { e: Int -> e + 10 })

    @Test
    fun t3LocalRebinding() = assertEquals(212, t3.runReader(100).runOrThrow())

    // t2 under each environment rebound in turn: the two rebindings interleave.
    private val t4 = addDouble(t2.local { e: Int -> e + 10 }, t2.localDouble { it + 30.0 })

    @Test
    fun t4InterleavedRebindingsHandledInEitherOrder() {
        assertEquals(106.0, t4.runReader(10).runReaderDouble(20.0).runOrThrow())
        assertEquals(106.0, t4.runReaderDouble(20.0).runReader(10).runOrThrow())
    }

    @Test
    fun tmapEffectfulMap() =
        assertEquals(
            listOf(11, 12, 13, 14, 15),
            mapM((1..5).toList()) { x -> add(ask(), Program.Done(x)) }.runReader(10).runOrThrow(),
        )

    // Adds the environment to an accumulator n times.
    private fun addGet(x: Int): Program<Int> = ask<Int>().map { it + x }

    private fun addN(n: Int): Program<Int> = (1..n).fold<Int, Program<Int>>(Program.Done(0)) { acc, _ -> acc.flatMap(::addGet) }

    private val rdwr =
        program {
            tell("begin").bind()
            val r = addN(10).bind()
            tell("end").bind()
            r
        }

    @Test
    fun rdwrReaderAndWriter() = assertEquals(10 to listOf("begin", "end"), rdwr.runWriter().runReader(1).runOrThrow())

    // Logs each element before mapping it.
    private fun mapMdebug(
        f: (Int) -> Program<Int>,
        items: List<Int>,
    ): Program<List<Int>> =
        if (items.isEmpty()) {
            Program.Done(emptyList())
        } else {
            program {
                tell("mapMdebug: ${items.first()}").bind()
                val head = f(items.first()).bind()
                val tail = mapMdebug(f, items.drop(1)).bind()
                listOf(head) + tail
            }
        }

    @Test
    fun tMdLoggedMap() =
        assertEquals(
            listOf(11, 12, 13, 14, 15) to (1..5).map { "mapMdebug: $it" },
            mapMdebug({ x -> add(ask(), Program.Done(x)) }, (1..5).toList()).runReader(10).runWriter().runOrThrow(),
        )

    // Two reader handlers of one environment type: the inner one answers only the asks inside it.
    @Test
    fun tdupNestedReaderHandlers() {
        val tr =
            program {
                val v = ask<Int>().bind()
                tell("Asked: $v").bind()
            }
        val m =
            program {
                tr.runReader(20).bind()
                tr.bind()
            }
        assertEquals(listOf("Asked: 20", "Asked: 10"), m.runReader(10).runWriter().map { it.second }.runOrThrow())
    }

    // ---- Exceptions

    @Test
    fun et1PureAddition() = assertEquals(3, add(Program.Done(1), Program.Done(2)).runOrThrow())

    @Test
    fun et21ThrowInsideAddition() = assertEquals(Left(2), add(Program.Done(1), throwError(2)).runError<Int, Int>().runOrThrow())

    data class TooBig(
        val value: Int,
    )

    // Rejects any value above five.
    private fun ex2(m: Program<Int>): Program<Int> = m.flatMap { v -> if (v > 5) throwError(TooBig(v)) else Program.Done(v) }

    @Test
    fun ex2ErrorAndReaderHandledInEitherOrder() {
        assertEquals(Right(5), ex2(ask()).runError<TooBig, Int>().runReader(5).runOrThrow())
        assertEquals(Left(TooBig(7)), ex2(ask()).runError<TooBig, Int>().runReader(7).runOrThrow())
        assertEquals(Left(TooBig(7)), ex2(ask()).runReader(7).runError<TooBig, Int>().runOrThrow())
    }

    // Tries the first program, then the second, and fails with both reasons joined when both fail.
    private fun alttry(
        first: Program<Int>,
        second: Program<Int>,
    ): Program<Int> = first.recover { e1 -> second.recover { e2 -> raise(e1 + e2) } }

    @Test
    fun alttryFallbackJoinsReasons() {
        val fail1 = add(Program.Done(1), raise("bummer1"))
        val fail2 = add(Program.Done(1), raise("bummer2"))
        assertEquals(Right(10), alttry(fail1, Program.Done(10)).attempt().toEither().runOrThrow())
        assertEquals(Right(10), alttry(Program.Done(10), fail2).attempt().toEither().runOrThrow())
        assertEquals(Right(10), alttry(Program.Done(10), Program.Done(20)).attempt().toEither().runOrThrow())
        assertEquals(Left("bummer1bummer2"), alttry(fail1, fail2).attempt().toEither().runOrThrow())
    }

    // ---- Nondeterminism

    @Test
    fun exc11ChoiceInsideAddition() = assertEquals(listOf(2, 3), add(Program.Done(1), choose(listOf(1, 2))).collectAll().runOrThrow())

    // The expected value below is derived by hand; nothing outside this project has confirmed it yet.
    @Test
    fun testCAGuardKeepsEvens() =
        assertEquals(
            listOf(2, 4, 6, 8, 10),
            choose((1..10).toList()).flatMap { i -> guard(i % 2 == 0).map { i } }.collectAll().runOrThrow(),
        )

    private val gen = choose((2..30).toList())

    // Keeps the numbers with no proper divisor: a soft cut over the search for one.
    private val testIfte =
        gen.flatMap { n ->
            ifte(gen.flatMap { d -> guard(d < n && n % d == 0) }, { mzero<Int>() }, Program.Done(n))
        }

    @Test
    fun testIftePrimesBySoftCut() = assertEquals(listOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29), testIfte.collectAll().runOrThrow())

    private fun <A> unsplit(split: Pair<A, Program<A>>?): Program<A> =
        if (split == null) mzero() else orElse(Program.Done(split.first), split.second)

    // Two branches that each log before answering.
    private val tsplit = orElse(tell("begin").map { 1 }, tell("end").map { 2 })

    // The four expected values below are derived by hand; nothing outside this project has confirmed them yet.
    @Test
    fun tsplitr10CollectInsideWriter() = assertEquals(listOf(1, 2) to listOf("begin", "end"), tsplit.collectAll().runWriter().runOrThrow())

    @Test
    fun tsplitr11SplitAndUnsplitInsideWriter() =
        assertEquals(
            listOf(1, 2) to listOf("begin", "end"),
            tsplit.msplit().flatMap { unsplit(it) }.collectAll().runWriter().runOrThrow(),
        )

    @Test
    fun tsplitr20WriterInsideCollect() =
        assertEquals(listOf(1 to listOf("begin"), 2 to listOf("end")), tsplit.runWriter().collectAll().runOrThrow())

    // Splitting runs the first branch before the choice is re-made, so its line is in the log of every later branch.
    @Test
    fun tsplitr21SplitAndUnsplitWriterInsideCollect() =
        assertEquals(
            listOf(1 to listOf("begin"), 2 to listOf("begin", "end")),
            tsplit.msplit().flatMap { unsplit(it) }.runWriter().collectAll().runOrThrow(),
        )

    // The same four, with the writer that builds its log on the way back, as the Haskell does.

    @Test
    fun tsplitr10CollectInsideWriterAfterwards() = assertEquals(listOf(1, 2) to listOf("begin", "end"), tsplit.collectAll().runWriterAfterwards().runOrThrow())

    @Test
    fun tsplitr11SplitAndUnsplitInsideWriterAfterwards() =
        assertEquals(
            listOf(1, 2) to listOf("begin", "end"),
            tsplit.msplit().flatMap { unsplit(it) }.collectAll().runWriterAfterwards().runOrThrow(),
        )

    @Test
    fun tsplitr20WriterAfterwardsInsideCollect() =
        assertEquals(listOf(1 to listOf("begin"), 2 to listOf("end")), tsplit.runWriterAfterwards().collectAll().runOrThrow())

    @Test
    fun tsplitr21SplitAndUnsplitWriterAfterwardsInsideCollect() =
        assertEquals(
            listOf(1 to listOf("begin"), 2 to listOf("begin", "end")),
            tsplit.msplit().flatMap { unsplit(it) }.runWriterAfterwards().collectAll().runOrThrow(),
        )

    // ---- Exceptions and nondeterminism together

    private val fiveSevenOne = choose(listOf(5, 7, 1))

    @Test
    fun ex2_2ErrorInsideCollect() =
        assertEquals(
            listOf(Right(5), Left(TooBig(7)), Right(1)),
            ex2(fiveSevenOne).runError<TooBig, Int>().collectAll().runOrThrow(),
        )

    @Test
    fun ex2_1CollectInsideError() = assertEquals(Left(TooBig(7)), ex2(fiveSevenOne).collectAll().runError<TooBig, List<Int>>().runOrThrow())

    // Recovers from values up to seven and rethrows larger ones.
    private fun exRec(m: Program<Int>): Program<Int> =
        m.catchError { e: TooBig -> if (e.value <= 7) Program.Done(e.value) else throwError(e) }

    @Test
    fun ex2r_2RecoveredCollectInsideError() =
        assertEquals(Right(listOf(5, 7, 1)), exRec(ex2(fiveSevenOne)).collectAll().runError<TooBig, List<Int>>().runOrThrow())

    @Test
    fun ex2r_2pRecoveredErrorInsideCollect() =
        assertEquals(
            listOf(Right(5), Right(7), Right(1)),
            exRec(ex2(fiveSevenOne)).runError<TooBig, Int>().collectAll().runOrThrow(),
        )

    @Test
    fun ex2r_1RethrownCollectInsideError() =
        assertEquals(
            Left(TooBig(11)),
            exRec(ex2(choose(listOf(5, 7, 11, 1)))).collectAll().runError<TooBig, List<Int>>().runOrThrow(),
        )

    // ---- State

    private val ts1 =
        program {
            putState(10).bind()
            getState<Int>().bind()
        }

    @Test
    fun ts1PutThenGet() = assertEquals(10 to 10, ts1.runStateWithFinal(0).runOrThrow())

    private val ts2 =
        program {
            putState(10).bind()
            val x = getState<Int>().bind()
            putState(20).bind()
            val y = getState<Int>().bind()
            x + y
        }

    @Test
    fun ts2TwoPutsTwoGets() = assertEquals(30 to 20, ts2.runStateWithFinal(0).runOrThrow())

    private val incr = modifyState<Int> { it + 1 }

    // Increments, then fails.
    private val tes1: Program<Nothing> = incr.flatMap { raise("exc") }

    @Test
    fun ter1FailureInsideState() = assertEquals(Left("exc") to 2, tes1.attempt().toEither().runStateWithFinal(1).runOrThrow())

    @Test
    fun ter2StateInsideFailure() = assertEquals(Left("exc"), tes1.runStateWithFinal(1).attempt().toEither().runOrThrow())

    // Reports "done", or the reason the program failed.
    private fun teCatch(m: Program<*>): Program<String> = m.map { "done" }.recover { Program.Done(it) }

    @Test
    fun ter3CaughtFailureInsideState() =
        assertEquals(Right("exc") to 2, teCatch(tes1).attempt().toEither().runStateWithFinal(1).runOrThrow())

    @Test
    fun ter4StateInsideCaughtFailure() =
        assertEquals(Right("exc" to 2), teCatch(tes1).runStateWithFinal(1).attempt().toEither().runOrThrow())

    // The two expected values below are this project's own, derived by hand; nothing outside it has confirmed them yet.
    @Test
    fun transactionDiscardsTheWritesOfAFailedProgram() =
        assertEquals(
            Right("exc") to 1,
            teCatch(tes1.transactional()).attempt().toEither().runStateWithFinal(1).runOrThrow(),
        )

    @Test
    fun transactionCommitsTheWritesOfAFinishedProgram() {
        val p =
            program {
                incr.flatMap { incr }.transactional().bind()
                getState<Int>().bind()
            }
        assertEquals(3 to 3, p.runStateWithFinal(1).runOrThrow())
    }

    @Test
    fun tfreshCounterFromZero() {
        val p =
            program {
                val a = fresh().bind()
                tell("Fresh $a").bind()
                val b = fresh().bind()
                tell("Fresh $b").bind()
            }
        assertEquals(listOf("Fresh 0", "Fresh 1"), p.runFresh(0).runWriter().map { it.second }.runOrThrow())
    }

    // ---- Generators

    // Logs each yielded value, lets the driver wrap the rest before resuming, and logs "Done" at the end.
    private fun drive(
        step: Generator<Int, Unit>,
        wrap: Wrap,
    ): Program<Unit> =
        when (step) {
            is Generator.Finished -> tell("Done")
            is Generator.Yielded ->
                tell(step.value.toString()).flatMap {
                    wrap(step.value, step.resume()).flatMap { drive(it, wrap) }
                }
        }

    private fun trace(
        th: Program<Unit>,
        wrap: Wrap = { _, rest -> rest },
    ): Program<Unit> = th.generator<Int, Unit>().flatMap { drive(it, wrap) }

    private fun <A> Program<A>.lines(): Program<List<String>> = runWriter().map { it.second }

    private val raiseByOne: Wrap = { _, rest -> rest.local { e: Int -> e + 1 } }

    private val th1 =
        program {
            yieldValue(1).bind()
            yieldValue(2).bind()
        }

    @Test
    fun c1TwoYields() = assertEquals(listOf("1", "2", "Done"), trace(th1).lines().runOrThrow())

    // Yields the environment.
    private val ay = ask<Int>().flatMap { yieldValue(it) }

    private val th2 =
        program {
            ay.bind()
            ay.bind()
        }

    @Test
    fun c2YieldsTheEnvironment() = assertEquals(listOf("10", "10", "Done"), trace(th2).runReader(10).lines().runOrThrow())

    @Test
    fun c21DriverRebindsTheRest() = assertEquals(listOf("10", "11", "Done"), trace(th2, raiseByOne).runReader(10).lines().runOrThrow())

    private val th3 =
        program {
            ay.bind()
            ay.bind()
            program {
                ay.bind()
                ay.bind()
            }.local { e: Int -> e + 10 }.bind()
        }

    @Test
    fun c3GeneratorRebindsItself() =
        assertEquals(listOf("10", "10", "20", "20", "Done"), trace(th3).runReader(10).lines().runOrThrow())

    @Test
    fun c31GeneratorAndDriverBothRebind() =
        assertEquals(listOf("10", "11", "21", "21", "Done"), trace(th3, raiseByOne).runReader(10).lines().runOrThrow())

    @Test
    fun c4ClientPassedIn() {
        val client =
            program {
                ay.bind()
                ay.bind()
            }
        val th4 =
            program {
                client.bind()
                client.local { e: Int -> e + 10 }.bind()
            }
        assertEquals(listOf("10", "11", "21", "21", "Done"), trace(th4, raiseByOne).runReader(10).lines().runOrThrow())
    }

    // Runs the client, then again under a raised environment while the environment is at most
    // twenty, and in that case recurses under a further raise.
    private fun th5(client: Program<Unit>): Program<Unit> =
        program {
            client.bind()
            val v = ask<Int>().bind()
            (if (v > 20) client else client.local { e: Int -> e + 5 }).bind()
            if (v <= 20) th5(client).local { e: Int -> e + 10 }.bind()
        }

    @Test
    fun c5DriverRebindsFromTheYieldedValue() {
        val client =
            program {
                ay.bind()
                ay.bind()
                ay.bind()
            }
        val fromYielded: Wrap = { x, rest -> rest.local { _: Int -> x + 1 } }
        assertEquals(
            listOf("10", "11", "12", "18", "18", "18") + List(6) { "29" } + "Done",
            trace(th5(client), fromYielded).runReader(10).lines().runOrThrow(),
        )
    }

    // Two environments: the driver rebinds the Double one from the yielded value, the generator the Int one.
    private val ayBoth =
        program {
            val x = ask<Int>().bind()
            val y = askDouble().bind()
            yieldValue(x + y.roundToInt()).bind()
        }

    private val clientBoth =
        program {
            ayBoth.bind()
            ayBoth.bind()
            ayBoth.bind()
        }

    private val fromYieldedDouble: Wrap = { x, rest -> rest.localDouble { (x + 1).toDouble() } }

    @Test
    fun c7TwoEnvironmentsRebound() =
        assertEquals(
            listOf(1010, 1021, 1032, 1048, 1064, 1080, 1101, 1122, 1143, 1169, 1195, 1221, 1252, 1283, 1314, 1345, 1376, 1407)
                .map { it.toString() } + "Done",
            trace(th5(clientBoth), fromYieldedDouble).runReader(10).runReaderDouble(1000.0).lines().runOrThrow(),
        )

    // As th5, but the inner rebinding is of the Double environment.
    private fun th7p(client: Program<Unit>): Program<Unit> =
        program {
            client.bind()
            val v = ask<Int>().bind()
            (if (v > 20) client else client.localDouble { it + 5.0 }).bind()
            if (v <= 20) th7p(client).local { e: Int -> e + 10 }.bind()
        }

    @Test
    fun c7pInnerRebindingOfTheOtherEnvironment() =
        assertEquals(
            listOf(1010, 1021, 1032, 1048, 1048, 1048, 1069, 1090, 1111, 1137, 1137, 1137, 1168, 1199, 1230, 1261, 1292, 1323)
                .map { it.toString() } + "Done",
            trace(th7p(clientBoth), fromYieldedDouble).runReader(10).runReaderDouble(1000.0).lines().runOrThrow(),
        )
}
