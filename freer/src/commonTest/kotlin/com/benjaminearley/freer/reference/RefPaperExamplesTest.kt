/*
 * The golden examples of PaperExamplesTest, run through the specification interpreter
 * instead of the library. The same answers from both are what makes the model a
 * usable oracle for the library.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.reference

import com.benjaminearley.freer.AskDouble
import com.benjaminearley.freer.Either
import com.benjaminearley.freer.Fresh
import com.benjaminearley.freer.Left
import com.benjaminearley.freer.Right
import com.benjaminearley.freer.Tell
import com.benjaminearley.freer.Throw
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

// The extra effects of the examples, handled by the model.

private fun tell(line: String): Ref<Unit> = send(Tell(line))

// Collects every told line next to the final value: a line goes in front of the lines its continuation produces.
private fun <A> Ref<A>.runWriter(): Ref<Pair<A, List<String>>> =
    handleRelay<Tell, A, Pair<A, List<String>>>({ pure(it to emptyList()) }) { op, resume ->
        resume(Unit).map { (value, lines) -> value to (listOf(op.line) + lines) }
    }

private fun <E> throwError(error: E): Ref<Nothing> = send(Throw(error))

private fun <E, A> Ref<A>.runError(): Ref<Either<E, A>> =
    handleRelay<Throw<*>, A, Either<E, A>>({ pure(Right(it)) }) { op, _ -> pure(Left(op.error as E)) }

private fun <E, A> Ref<A>.catchError(handler: (E) -> Ref<A>): Ref<A> =
    interpose<Throw<*>, A, A>({ pure(it) }) { op, _ -> handler(op.error as E) }

private fun askDouble(): Ref<Double> = send(AskDouble)

private fun <A> Ref<A>.runReaderDouble(env: Double): Ref<A> = handleRelay<AskDouble, A, A>({ pure(it) }) { _, resume -> resume(env) }

private fun <A> Ref<A>.localDouble(transform: (Double) -> Double): Ref<A> =
    askDouble().flatMap { outer ->
        val env = transform(outer)
        this@localDouble.interpose<AskDouble, A, A>({ pure(it) }) { _, resume -> resume(env) }
    }

private fun fresh(): Ref<Int> = send(Fresh)

private fun <A> Ref<A>.runFresh(start: Int): Ref<A> =
    handleRelayS<Fresh, Int, A, A>(start, { _, value -> pure(value) }) { next, _, resume -> resume(next + 1, next) }

// What a driver does with the rest of a generator before resuming it, given the value just yielded.
private typealias Wrap = (Int, Ref<Coroutine<Int, Unit>>) -> Ref<Coroutine<Int, Unit>>

class RefPaperExamplesTest {
    private fun add(
        a: Ref<Int>,
        b: Ref<Int>,
    ): Ref<Int> = a.flatMap { x -> b.map { y -> x + y } }

    private fun addDouble(
        a: Ref<Double>,
        b: Ref<Double>,
    ): Ref<Double> = a.flatMap { x -> b.map { y -> x + y } }

    private fun <A, B> mapM(
        items: List<A>,
        f: (A) -> Ref<B>,
    ): Ref<List<B>> =
        items.fold<A, Ref<List<B>>>(pure(emptyList())) { acc, item ->
            acc.flatMap { done -> f(item).map { done + it } }
        }

    private fun <A> mzero(): Ref<A> = choose(emptyList())

    // ---- Reader

    private val t1 = add(ask(), pure(1))

    @Test
    fun t1AskPlusOne() = assertEquals(11, t1.runReader(10).run())

    private val t2 = ask<Int>().flatMap { v1 -> askDouble().map { v2 -> (v1 + 1).toDouble() + (v2 + 2) } }

    @Test
    fun t2TwoEnvironmentsHandledInEitherOrder() {
        assertEquals(33.0, t2.runReader(10).runReaderDouble(20.0).run())
        assertEquals(33.0, t2.runReaderDouble(20.0).runReader(10).run())
    }

    private val t3 = add(t1, t1.local { e: Int -> e + 10 })

    @Test
    fun t3LocalRebinding() = assertEquals(212, t3.runReader(100).run())

    private val t4 = addDouble(t2.local { e: Int -> e + 10 }, t2.localDouble { it + 30.0 })

    @Test
    fun t4InterleavedRebindingsHandledInEitherOrder() {
        assertEquals(106.0, t4.runReader(10).runReaderDouble(20.0).run())
        assertEquals(106.0, t4.runReaderDouble(20.0).runReader(10).run())
    }

    @Test
    fun tmapEffectfulMap() =
        assertEquals(listOf(11, 12, 13, 14, 15), mapM((1..5).toList()) { x -> add(ask(), pure(x)) }.runReader(10).run())

    private fun addGet(x: Int): Ref<Int> = ask<Int>().map { it + x }

    private fun addN(n: Int): Ref<Int> = (1..n).fold<Int, Ref<Int>>(pure(0)) { acc, _ -> acc.flatMap(::addGet) }

    private val rdwr = tell("begin").flatMap { addN(10) }.flatMap { r -> tell("end").map { r } }

    @Test
    fun rdwrReaderAndWriter() = assertEquals(10 to listOf("begin", "end"), rdwr.runWriter().runReader(1).run())

    private fun mapMdebug(
        f: (Int) -> Ref<Int>,
        items: List<Int>,
    ): Ref<List<Int>> =
        if (items.isEmpty()) {
            pure(emptyList())
        } else {
            tell("mapMdebug: ${items.first()}")
                .flatMap { f(items.first()) }
                .flatMap { head -> mapMdebug(f, items.drop(1)).map { tail -> listOf(head) + tail } }
        }

    @Test
    fun tMdLoggedMap() =
        assertEquals(
            listOf(11, 12, 13, 14, 15) to (1..5).map { "mapMdebug: $it" },
            mapMdebug({ x -> add(ask(), pure(x)) }, (1..5).toList()).runReader(10).runWriter().run(),
        )

    @Test
    fun tdupNestedReaderHandlers() {
        val tr = ask<Int>().flatMap { v -> tell("Asked: $v") }
        val m = tr.runReader(20).flatMap { tr }
        assertEquals(listOf("Asked: 20", "Asked: 10"), m.runReader(10).runWriter().map { it.second }.run())
    }

    // ---- Exceptions

    @Test
    fun et1PureAddition() = assertEquals(3, add(pure(1), pure(2)).run())

    @Test
    fun et21ThrowInsideAddition() = assertEquals(Left(2), add(pure(1), throwError(2)).runError<Int, Int>().run())

    data class TooBig(
        val value: Int,
    )

    private fun ex2(m: Ref<Int>): Ref<Int> = m.flatMap { v -> if (v > 5) throwError(TooBig(v)) else pure(v) }

    @Test
    fun ex2ErrorAndReaderHandledInEitherOrder() {
        assertEquals(Right(5), ex2(ask()).runError<TooBig, Int>().runReader(5).run())
        assertEquals(Left(TooBig(7)), ex2(ask()).runError<TooBig, Int>().runReader(7).run())
        assertEquals(Left(TooBig(7)), ex2(ask()).runReader(7).runError<TooBig, Int>().run())
    }

    private fun alttry(
        first: Ref<Int>,
        second: Ref<Int>,
    ): Ref<Int> = first.recover { e1 -> second.recover { e2 -> raise(e1 + e2) } }

    @Test
    fun alttryFallbackJoinsReasons() {
        val fail1 = add(pure(1), raise("bummer1"))
        val fail2 = add(pure(1), raise("bummer2"))
        assertEquals(Right(10), alttry(fail1, pure(10)).attempt().run())
        assertEquals(Right(10), alttry(pure(10), fail2).attempt().run())
        assertEquals(Right(10), alttry(pure(10), pure(20)).attempt().run())
        assertEquals(Left("bummer1bummer2"), alttry(fail1, fail2).attempt().run())
    }

    // ---- Nondeterminism

    @Test
    fun exc11ChoiceInsideAddition() = assertEquals(listOf(2, 3), add(pure(1), choose(listOf(1, 2))).collectAll().run())

    @Test
    fun testCAGuardKeepsEvens() =
        assertEquals(listOf(2, 4, 6, 8, 10), choose((1..10).toList()).flatMap { i -> guard(i % 2 == 0).map { i } }.collectAll().run())

    private val gen = choose((2..30).toList())

    private val testIfte =
        gen.flatMap { n ->
            ifte(gen.flatMap { d -> guard(d < n && n % d == 0) }, { mzero<Int>() }, pure(n))
        }

    @Test
    fun testIftePrimesBySoftCut() = assertEquals(listOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29), testIfte.collectAll().run())

    private val tsplit = orElse(tell("begin").map { 1 }, tell("end").map { 2 })

    @Test
    fun tsplitr10CollectInsideWriter() = assertEquals(listOf(1, 2) to listOf("begin", "end"), tsplit.collectAll().runWriter().run())

    @Test
    fun tsplitr11SplitAndUnsplitInsideWriter() =
        assertEquals(listOf(1, 2) to listOf("begin", "end"), tsplit.msplit().flatMap { unsplit(it) }.collectAll().runWriter().run())

    @Test
    fun tsplitr20WriterInsideCollect() =
        assertEquals(listOf(1 to listOf("begin"), 2 to listOf("end")), tsplit.runWriter().collectAll().run())

    @Test
    fun tsplitr21SplitAndUnsplitWriterInsideCollect() =
        assertEquals(
            listOf(1 to listOf("begin"), 2 to listOf("begin", "end")),
            tsplit.msplit().flatMap { unsplit(it) }.runWriter().collectAll().run(),
        )

    // ---- Exceptions and nondeterminism together

    private val fiveSevenOne = choose(listOf(5, 7, 1))

    @Test
    fun ex2_2ErrorInsideCollect() =
        assertEquals(listOf(Right(5), Left(TooBig(7)), Right(1)), ex2(fiveSevenOne).runError<TooBig, Int>().collectAll().run())

    @Test
    fun ex2_1CollectInsideError() = assertEquals(Left(TooBig(7)), ex2(fiveSevenOne).collectAll().runError<TooBig, List<Int>>().run())

    private fun exRec(m: Ref<Int>): Ref<Int> = m.catchError { e: TooBig -> if (e.value <= 7) pure(e.value) else throwError(e) }

    @Test
    fun ex2r_2RecoveredCollectInsideError() =
        assertEquals(Right(listOf(5, 7, 1)), exRec(ex2(fiveSevenOne)).collectAll().runError<TooBig, List<Int>>().run())

    @Test
    fun ex2r_2pRecoveredErrorInsideCollect() =
        assertEquals(listOf(Right(5), Right(7), Right(1)), exRec(ex2(fiveSevenOne)).runError<TooBig, Int>().collectAll().run())

    @Test
    fun ex2r_1RethrownCollectInsideError() =
        assertEquals(Left(TooBig(11)), exRec(ex2(choose(listOf(5, 7, 11, 1)))).collectAll().runError<TooBig, List<Int>>().run())

    // ---- State

    private val ts1 = putState(10).flatMap { getState<Int>() }

    @Test
    fun ts1PutThenGet() = assertEquals(10 to 10, ts1.runStateWithFinal(0).run())

    private val ts2 =
        putState(10).flatMap { getState<Int>() }.flatMap { x ->
            putState(20).flatMap { getState<Int>() }.map { y -> x + y }
        }

    @Test
    fun ts2TwoPutsTwoGets() = assertEquals(30 to 20, ts2.runStateWithFinal(0).run())

    private val incr = modifyState<Int> { it + 1 }

    private val tes1: Ref<Nothing> = incr.flatMap { raise("exc") }

    @Test
    fun ter1FailureInsideState() = assertEquals(Left("exc") to 2, tes1.attempt().runStateWithFinal(1).run())

    @Test
    fun ter2StateInsideFailure() = assertEquals(Left("exc"), tes1.runStateWithFinal(1).attempt().run())

    private fun teCatch(m: Ref<*>): Ref<String> = m.map { "done" }.recover { pure(it) }

    @Test
    fun ter3CaughtFailureInsideState() = assertEquals(Right("exc") to 2, teCatch(tes1).attempt().runStateWithFinal(1).run())

    @Test
    fun ter4StateInsideCaughtFailure() = assertEquals(Right("exc" to 2), teCatch(tes1).runStateWithFinal(1).attempt().run())

    @Test
    fun transactionDiscardsTheWritesOfAFailedProgram() =
        assertEquals(Right("exc") to 1, teCatch(tes1.transactional()).attempt().runStateWithFinal(1).run())

    @Test
    fun transactionCommitsTheWritesOfAFinishedProgram() =
        assertEquals(3 to 3, incr.flatMap { incr }.transactional().flatMap { getState<Int>() }.runStateWithFinal(1).run())

    @Test
    fun tfreshCounterFromZero() {
        val p = fresh().flatMap { a -> tell("Fresh $a") }.flatMap { fresh() }.flatMap { b -> tell("Fresh $b") }
        assertEquals(listOf("Fresh 0", "Fresh 1"), p.runFresh(0).runWriter().map { it.second }.run())
    }

    // ---- Generators

    private fun drive(
        step: Coroutine<Int, Unit>,
        wrap: Wrap,
    ): Ref<Unit> =
        when (step) {
            is Coroutine.Finished -> tell("Done")
            is Coroutine.Yielded ->
                tell(step.value.toString()).flatMap {
                    wrap(step.value, step.resume()).flatMap { drive(it, wrap) }
                }
        }

    private fun trace(
        th: Ref<Unit>,
        wrap: Wrap = { _, rest -> rest },
    ): Ref<Unit> = th.generator<Int, Unit>().flatMap { drive(it, wrap) }

    private fun <A> Ref<A>.lines(): Ref<List<String>> = runWriter().map { it.second }

    private val raiseByOne: Wrap = { _, rest -> rest.local { e: Int -> e + 1 } }

    private val th1 = yieldValue(1).flatMap { yieldValue(2) }

    @Test
    fun c1TwoYields() = assertEquals(listOf("1", "2", "Done"), trace(th1).lines().run())

    private val ay = ask<Int>().flatMap { yieldValue(it) }

    private val th2 = ay.flatMap { ay }

    @Test
    fun c2YieldsTheEnvironment() = assertEquals(listOf("10", "10", "Done"), trace(th2).runReader(10).lines().run())

    @Test
    fun c21DriverRebindsTheRest() = assertEquals(listOf("10", "11", "Done"), trace(th2, raiseByOne).runReader(10).lines().run())

    private val th3 = ay.flatMap { ay }.flatMap { ay.flatMap { ay }.local { e: Int -> e + 10 } }

    @Test
    fun c3GeneratorRebindsItself() = assertEquals(listOf("10", "10", "20", "20", "Done"), trace(th3).runReader(10).lines().run())

    @Test
    fun c31GeneratorAndDriverBothRebind() =
        assertEquals(listOf("10", "11", "21", "21", "Done"), trace(th3, raiseByOne).runReader(10).lines().run())

    @Test
    fun c4ClientPassedIn() {
        val client = ay.flatMap { ay }
        val th4 = client.flatMap { client.local { e: Int -> e + 10 } }
        assertEquals(listOf("10", "11", "21", "21", "Done"), trace(th4, raiseByOne).runReader(10).lines().run())
    }

    private fun th5(client: Ref<Unit>): Ref<Unit> =
        client.flatMap { ask<Int>() }.flatMap { v ->
            (if (v > 20) client else client.local { e: Int -> e + 5 }).flatMap {
                if (v > 20) pure(Unit) else th5(client).local { e: Int -> e + 10 }
            }
        }

    @Test
    fun c5DriverRebindsFromTheYieldedValue() {
        val client = ay.flatMap { ay }.flatMap { ay }
        val fromYielded: Wrap = { x, rest -> rest.local { _: Int -> x + 1 } }
        assertEquals(
            listOf("10", "11", "12", "18", "18", "18") + List(6) { "29" } + "Done",
            trace(th5(client), fromYielded).runReader(10).lines().run(),
        )
    }

    private val ayBoth = ask<Int>().flatMap { x -> askDouble().flatMap { y -> yieldValue(x + y.roundToInt()) } }

    private val clientBoth = ayBoth.flatMap { ayBoth }.flatMap { ayBoth }

    private val fromYieldedDouble: Wrap = { x, rest -> rest.localDouble { (x + 1).toDouble() } }

    @Test
    fun c7TwoEnvironmentsRebound() =
        assertEquals(
            listOf(1010, 1021, 1032, 1048, 1064, 1080, 1101, 1122, 1143, 1169, 1195, 1221, 1252, 1283, 1314, 1345, 1376, 1407)
                .map { it.toString() } + "Done",
            trace(th5(clientBoth), fromYieldedDouble).runReader(10).runReaderDouble(1000.0).lines().run(),
        )

    private fun th7p(client: Ref<Unit>): Ref<Unit> =
        client.flatMap { ask<Int>() }.flatMap { v ->
            (if (v > 20) client else client.localDouble { it + 5.0 }).flatMap {
                if (v > 20) pure(Unit) else th7p(client).local { e: Int -> e + 10 }
            }
        }

    @Test
    fun c7pInnerRebindingOfTheOtherEnvironment() =
        assertEquals(
            listOf(1010, 1021, 1032, 1048, 1048, 1048, 1069, 1090, 1111, 1137, 1137, 1137, 1168, 1199, 1230, 1261, 1292, 1323)
                .map { it.toString() } + "Done",
            trace(th7p(clientBoth), fromYieldedDouble).runReader(10).runReaderDouble(1000.0).lines().run(),
        )
}
