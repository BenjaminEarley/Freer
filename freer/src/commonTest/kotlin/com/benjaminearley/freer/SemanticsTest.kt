/**
 * Semantics tests: interpreter-rule contract enforcement and behaviors
 * not covered by the stack-safety tests in TrampolineTest.kt.
 */

package com.benjaminearley.freer

import com.benjaminearley.freer.effects.NonDet
import com.benjaminearley.freer.effects.Status
import com.benjaminearley.freer.effects.ask
import com.benjaminearley.freer.effects.catchError
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.coroutine
import com.benjaminearley.freer.effects.fail
import com.benjaminearley.freer.effects.getState
import com.benjaminearley.freer.effects.guard
import com.benjaminearley.freer.effects.ifte
import com.benjaminearley.freer.effects.io
import com.benjaminearley.freer.effects.local
import com.benjaminearley.freer.effects.modifyState
import com.benjaminearley.freer.effects.msplit
import com.benjaminearley.freer.effects.once
import com.benjaminearley.freer.effects.orElse
import com.benjaminearley.freer.effects.performIO
import com.benjaminearley.freer.effects.putState
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.effects.runReader
import com.benjaminearley.freer.effects.runState
import com.benjaminearley.freer.effects.runStateWithFinal
import com.benjaminearley.freer.effects.transactional
import com.benjaminearley.freer.effects.yieldValue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticsTest {
    private fun askPlusOne() = perform(AskInt).flatMap { n -> Program.Done(n + 1) }

    private val memThenFail =
        program {
            putState(2).bind()
            fail("boom").bind()
        }

    private val statefulBranches =
        choose(listOf(10, 20, 30)).flatMap { x ->
            getState<Int>().flatMap { before ->
                putState(before + x).flatMap {
                    getState<Int>().map { after -> Triple(x, before, after) }
                }
            }
        }

    @Test
    fun directResumeReturnedVerbatimWorks() {
        val result =
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(41)
                    }
                }.runOrThrow()
        assertEquals(42, result)
    }

    @Test
    fun transformingDirectResumeResultThrows() {
        // Not silent corruption
        assertContractViolation {
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(41).map { it }
                    }
                }.runOrThrow()
        }
    }

    @Test
    fun discardingDirectResumeResultThrows() {
        // Not a lost continuation
        assertContractViolation {
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(41)
                            Program.Done(99)
                        }
                    }
                }.runOrThrow()
        }
    }

    @Test
    fun callingResumeDirectlyTwiceThrows() {
        // Not last-branch-wins
        assertContractViolation {
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(1)
                            resume(2)
                        }
                    }
                }.runOrThrow()
        }
    }

    @Test
    fun sameContractHoldsForInterpretS() {
        assertContractViolation {
            askPlusOne()
                .interpretS<Ask<*>, Int, Int>(0) { s, op, resume ->
                    when (op) {
                        is AskInt -> resume(s, 41).map { it }
                    }
                }.runOrThrow()
        }
        assertContractViolation {
            askPlusOne()
                .interpretS<Ask<*>, Int, Int>(0) { s, op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(s, 41)
                            Program.Done(99)
                        }
                    }
                }.runOrThrow()
        }
        assertContractViolation {
            askPlusOne()
                .interpretS<Ask<*>, Int, Int>(0) { s, op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(s, 1)
                            resume(s, 2)
                        }
                    }
                }.runOrThrow()
        }
    }

    @Test
    fun storedResumeStaysUsableAfterRuleReturnsAndIsMultiShot() {
        // Short-circuit the interpretation but capture the continuation; hand-built
        // programs have pure pipelines, so the deferred continuation can be invoked
        // any number of times, each producing an independent result.
        var saved: ((Erased) -> Program<Int>)? = null
        val shortCircuited =
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            saved = resume
                            Program.Done(-1)
                        }
                    }
                }.runOrThrow()
        assertEquals(-1, shortCircuited)

        val continuation = assertNotNull(saved)
        assertEquals(6, continuation(5).runOrThrow())
        assertEquals(11, continuation(10).runOrThrow())
    }

    @Test
    fun programBlockValuesAreReplayablePureDescriptions() {
        val prog =
            program {
                val a = perform(AskInt).bind()
                val b = perform(AskInt).bind()
                a + b
            }

        fun runWith(n: Int): Int =
            prog
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(n)
                    }
                }.runOrThrow()

        // Before Defer, the second run silently returned 11 (only the code after
        // the last bind re-ran, with stale locals from run 1).
        assertEquals(2, runWith(1))
        assertEquals(20, runWith(10))
        assertEquals(2, runWith(1))

        // Replay also works across different handler stacks (handle DSL).
        assertEquals(10, prog.handle<Ask<*>, Int> { 5 }.runOrThrow())
        assertEquals(100, prog.handle<Ask<*>, Int> { 50 }.runOrThrow())
    }

    @Test
    fun handlerApplicationIsLazyAndHandledValuesAreReusable() {
        var handlerRuns = 0
        val handled =
            program { perform(AskInt).bind() }
                .handle<Ask<*>, Int> {
                    handlerRuns++
                    3
                }
        assertEquals(0, handlerRuns, "Handler ran eagerly at application time")

        assertEquals(3, handled.runOrThrow())
        assertEquals(3, handled.runOrThrow())
        assertEquals(2, handlerRuns)

        // A stored PARTIALLY-handled stack is also a reusable description: the
        // remaining effect can be handled and run any number of times.
        var audits = 0
        val partial =
            program { perform(AskInt).bind() }
                .intercept<Ask<*>, Int> { _, proceed ->
                    audits++
                    proceed()
                }
        repeat(2) {
            assertEquals(7, partial.handle<Ask<*>, Int> { 7 }.runOrThrow())
        }
        assertEquals(2, audits)
    }

    @Test
    fun bindingProgramBlockReturnedInsideFlatMapWorks() {
        // Defer in pipeline
        val prog =
            perform(AskInt).flatMap { n ->
                program { n + perform(AskInt).bind() }
            }
        val result =
            prog
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(5)
                    }
                }.runOrThrow()
        assertEquals(10, result)
    }

    @Test
    fun programBlocksRunPerInterpretationNotAtConstruction() {
        var runs = 0
        val prog =
            program {
                runs++
                perform(AskInt).bind()
            }
        assertEquals(0, runs, "Block ran eagerly at construction")

        repeat(2) {
            prog
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(7)
                    }
                }.runOrThrow()
        }
        assertEquals(2, runs)
    }

    @Test
    fun resumingOneProgramBlockSuspensionTwiceThrows() {
        // A program{} block is a single-shot coroutine
        var saved: ((Erased) -> Program<Int>)? = null
        val shortCircuited =
            program { perform(AskInt).bind() + 1 }
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            saved = resume
                            Program.Done(-1)
                        }
                    }
                }.runOrThrow()
        assertEquals(-1, shortCircuited)

        val continuation = assertNotNull(saved)
        assertEquals(6, continuation(5).runOrThrow())
        val e = assertFailsWith<IllegalStateException> { continuation(7).runOrThrow() }
        assertContains(e.message.orEmpty(), "single-shot")
    }

    @Test
    fun notCallingResumeShortCircuits() {
        // Raise-style
        val result =
            program {
                perform(AskInt).bind()
                error("unreachable: the continuation must not run")
            }.interpret<Ask<*>, Any?> { op, _ ->
                when (op) {
                    is AskInt -> Program.Done("stopped")
                }
            }.runOrThrow()
        assertEquals("stopped", result)
    }

    @Test
    fun storeRejectsWritesOfTheWrongTypeAtTheHandler() {
        val db = mutableMapOf("Alice" to 1000.0)
        val e =
            assertFailsWith<IllegalStateException> {
                program { storePut("Alice", "not a number").bind() }.testStore(db).runOrThrow()
            }
        assertContains(e.message.orEmpty(), "TestStore")
        assertEquals(1000.0, db["Alice"], "Map was corrupted")
    }

    @Test
    fun storeRejectsReadsWhoseExpectedTypeMismatchesTheStoredValue() {
        // Without the check, the stored Double would be coerced to an Int and the
        // program would return 1001.
        val db = mutableMapOf("k" to 1000.0)
        val e =
            assertFailsWith<IllegalStateException> {
                program { storeGet("k", 0).bind() + 1 }.testStore(db).runOrThrow()
            }
        assertContains(e.message.orEmpty(), "TestStore")
    }

    @Test
    fun storeReadCheckSkipsStructuredValues() {
        // No polymorphism false positive
        val db = mutableMapOf<String, List<Int>>("k" to listOf(1, 2))
        val stored = program { storeGet("k", emptyList<Int>()).bind() }.testStore(db).runOrThrow()
        assertEquals(listOf(1, 2), stored)
    }

    @Test
    fun storeReturnsStoredNullInsteadOfTheDefault() {
        val db = mutableMapOf<String, Double?>("k" to null)
        val stored = program { storeGet<Double?>("k", 5.0).bind() }.testStore(db).runOrThrow()
        assertNull(stored, "Stored null was replaced by the default")

        val absent = program { storeGet<Double?>("missing", 5.0).bind() }.testStore(db).runOrThrow()
        assertEquals(5.0, absent, "Absent key did not fall back to the default")
    }

    @Test
    fun handleSThreadsStateThroughTheDsl() {
        // runState is built on handleS
        val prog =
            program {
                putState(1).bind()
                val a = getState<Int>().bind()
                putState(a + 10).bind()
                getState<Int>().bind()
            }
        assertEquals(11, prog.runState(0).runOrThrow())

        val initialOnly = program { getState<Int>().bind() }.runState(42).runOrThrow()
        assertEquals(42, initialOnly, "Expected the initial state")
    }

    @Test
    fun interceptCanObserveAndRewriteTheDownstreamResponse() {
        val result =
            program { perform(AskInt).bind() }
                .intercept<Ask<*>, Int> { op, proceed ->
                    when (op) {
                        is AskInt -> (proceed() as Int) * 10
                    }
                }.handle<Ask<*>, Int> { 4 }
                .runOrThrow()
        assertEquals(40, result)
    }

    @Test
    fun interceptCanAnswerDirectlyWithoutForwarding() {
        val result =
            program { perform(AskInt).bind() }
                .intercept<Ask<*>, Int> { op, _ ->
                    when (op) {
                        is AskInt -> 99
                    }
                }.handle<Ask<*>, Int> { error("downstream handler must not run") }
                .runOrThrow()
        assertEquals(99, result)
    }

    @Test
    fun interpretTransformDoneMapsTheFinalValue() {
        val result =
            program { perform(AskInt).bind() }
                .interpret<Ask<*>, Int, String>(
                    transformDone = { Program.Done("value=$it") },
                    rule = { op, resume ->
                        when (op) {
                            is AskInt -> resume(6)
                        }
                    },
                ).runOrThrow()
        assertEquals("value=6", result)
    }

    @Test
    fun runCapturesTheOutcomeAsResult() {
        val ok = program { perform(AskInt).bind() }.handle<Ask<*>, Int> { 7 }.run()
        assertEquals(Result.success(7), ok)

        val unhandled = program { perform(AskInt).bind() }.run()
        val failure = assertIs<IllegalStateException>(unhandled.exceptionOrNull())
        assertContains(failure.message.orEmpty(), "AskInt")
    }

    @Test
    fun performIOThunksRunAtTheIoEdgeNotBefore() {
        val order = mutableListOf<String>()
        val prog =
            program {
                val n =
                    performIO {
                        order.add("io")
                        21
                    }.bind()
                n * 2
            }
        order.add("before")
        val result = runSync { prog.io() }
        assertEquals(42, result)
        assertEquals(listOf("before", "io"), order)

        val e = assertFailsWith<IllegalStateException> { runSync { program { perform(AskInt).bind() }.io() } }
        assertContains(e.message.orEmpty(), "io()")
    }

    @Test
    fun catchErrorSubstitutesTheRecoveryProgram() {
        val result =
            program { fail("down").bind() }
                .catchError { reason -> Program.Done("fallback:$reason") }
                .runOrThrow()
        assertEquals("fallback:down", result)
    }

    @Test
    fun failureInsideRecoveryPropagatesToTheOuterHandler() {
        val result =
            program { fail("down").bind() }
                .catchError { fail("worse") }
                .raise()
                .runOrThrow()
        assertEquals("worse", result.exceptionOrNull()?.message)
    }

    @Test
    fun handlerOrderRaiseInsideStateKeepsStateWrittenBeforeTheFailure() {
        val (result, state) = memThenFail.raise().runStateWithFinal(0).runOrThrow()
        assertTrue(result.isFailure, "got $result")
        assertEquals(2, state)
    }

    @Test
    fun handlerOrderStateInsideRaiseRollsTheStateBack() {
        val result = memThenFail.runStateWithFinal(0).raise().runOrThrow()
        assertTrue(result.isFailure, "got $result")
    }

    @Test
    fun coroutineReifiesYieldsAsStatusAndResumesOnDemand() {
        val gen =
            program {
                yieldValue(1).bind()
                yieldValue(2).bind()
                "end"
            }
        val seen = mutableListOf<Int>()
        var status = gen.coroutine<Int, String>().runOrThrow()
        while (status is Status.Yielded) {
            seen += status.value
            status = status.resume().runOrThrow()
        }
        assertEquals(listOf(1, 2), seen)
        assertEquals("end", assertIs<Status.Finished<*>>(status).result)
    }

    @Test
    fun runStateCarriesAnyStateTypeAndModifyStateRewritesIt() {
        data class Account(
            val owner: String,
            val balance: Int,
        )

        val prog =
            program {
                modifyState<Account> { it.copy(balance = it.balance + 50) }.bind()
                val seen = getState<Account>().bind()
                putState(seen.copy(owner = seen.owner.uppercase())).bind()
                modifyState<Account> { it.copy(balance = it.balance * 2) }.bind()
                seen.balance
            }
        val (seen, final) = prog.runStateWithFinal(Account("ann", 100)).runOrThrow()
        assertEquals(150, seen)
        assertEquals(Account("ANN", 300), final)
        assertEquals(150, prog.runState(Account("ann", 100)).runOrThrow())
    }

    @Test
    fun transactionalCommitsAndRollsBackANonIntState() {
        val appendThenFail =
            program {
                modifyState<List<String>> { it + "draft" }.bind()
                fail("rejected").bind()
            }
        val (_, rolledBack) = appendThenFail.transactional().raise().runStateWithFinal(listOf("a")).runOrThrow()
        assertEquals(listOf("a"), rolledBack)

        val append = modifyState<List<String>> { it + "b" }
        val (_, committed) = append.transactional().runStateWithFinal(listOf("a")).runOrThrow()
        assertEquals(listOf("a", "b"), committed)
    }

    @Test
    fun runReaderCarriesAnyEnvironmentTypeAndLocalTransformsIt() {
        data class Config(
            val host: String,
            val port: Int,
        )

        val address = ask<Config>().map { "${it.host}:${it.port}" }
        val combined =
            program {
                val inner = address.local { c: Config -> c.copy(port = c.port + 1) }.bind()
                val outer = address.bind()
                inner to outer
            }.runReader(Config("localhost", 8080)).runOrThrow()
        assertEquals("localhost:8081" to "localhost:8080", combined)
    }

    @Test
    fun coroutineYieldsValuesOfAnyType() {
        val gen =
            program {
                yieldValue("a").bind()
                yieldValue("b").bind()
                3
            }
        val seen = mutableListOf<String>()
        var status = gen.coroutine<String, Int>().runOrThrow()
        while (status is Status.Yielded) {
            seen += status.value
            status = status.resume().runOrThrow()
        }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(3, assertIs<Status.Finished<Int>>(status).result)
    }

    @Test
    fun collectAllResumesOneContinuationOncePerOption() {
        // Multi-shot
        val prog =
            choose(listOf(1, 2, 3)).flatMap { x ->
                choose(listOf(10, 20)).map { y -> x * y }
            }
        assertEquals(listOf(10, 20, 20, 40, 30, 60), prog.collectAll().runOrThrow())
    }

    @Test
    fun emptyChoosePrunesTheBranch() {
        val prog = choose(listOf(1, 2, 3, 4)).flatMap { x -> guard(x % 2 == 0).map { x } }
        assertEquals(listOf(2, 4), prog.collectAll().runOrThrow())
    }

    @Test
    fun catchErrorRecoveryEffectsNeedHandlersAppliedOutsideTheCatch() {
        val failing =
            program {
                fail("down").bind()
                getState<Int>().bind()
            }
        val recovery = { _: String -> program { putState(9).bind(); getState<Int>().bind() } }

        // A handler already applied beneath the catch never sees the recovery program
        val beneath = failing.runState(0).catchError(recovery).run()
        assertContains(beneath.exceptionOrNull()?.message.orEmpty(), "Unhandled effect")

        // A handler applied outside the catch handles it
        assertEquals(9, failing.catchError(recovery).runState(0).runOrThrow())
    }

    @Test
    fun multiShotOrderingStateInsideCollectAllBacktracksPerBranch() {
        val results = statefulBranches.runState(0).collectAll().runOrThrow()
        assertEquals(listOf(Triple(10, 0, 10), Triple(20, 0, 20), Triple(30, 0, 30)), results)
    }

    @Test
    fun multiShotOrderingStateOutsideCollectAllThreadsGloballyAcrossBranches() {
        val results = statefulBranches.collectAll().runState(0).runOrThrow()
        assertEquals(listOf(Triple(10, 0, 10), Triple(20, 10, 30), Triple(30, 30, 60)), results)
    }

    @Test
    fun multiShotOrderingCatchErrorInsideRecoversPerBranchOutsideReplacesAll() {
        val branches: Program<Int> =
            choose(listOf(1, 2, 3)).flatMap { x ->
                if (x == 2) fail("branch 2 failed") else Program.Done(x)
            }

        val perBranch = branches.catchError { Program.Done(-1) }.collectAll().runOrThrow()
        assertEquals(listOf(1, -1, 3), perBranch)

        val wholeCollection = branches.collectAll().catchError { Program.Done(listOf(-1)) }.runOrThrow()
        assertEquals(listOf(-1), wholeCollection)
    }

    @Test
    fun transactionalRollsBackPartialWritesOnFailureAndCommitsOnSuccess() {
        val depositThenFail =
            program {
                putState(getState<Int>().bind() + 100).bind()
                fail("validation").bind()
                "unreachable"
            }

        val (_, leaked) = depositThenFail.raise().runStateWithFinal(500).runOrThrow()
        assertEquals(600, leaked, "unprotected")

        val (_, rolledBack) = depositThenFail.transactional().raise().runStateWithFinal(500).runOrThrow()
        assertEquals(500, rolledBack, "transactional")

        val deposit = program { putState(getState<Int>().bind() + 100).bind(); "ok" }
        val (ok, committed) = deposit.transactional().raise().runStateWithFinal(500).runOrThrow()
        assertEquals("ok", ok.getOrNull())
        assertEquals(600, committed)
    }

    @Test
    fun readerLocalOverridesTheEnvironmentOnlyInsideTheSubProgram() {
        val greet = program { ask<String>().bind() }
        val combined =
            program {
                val inner = greet.local { e: String -> e.uppercase() }.bind()
                val outer = greet.bind()
                inner to outer
            }.runReader("env").runOrThrow()
        assertEquals("ENV" to "env", combined)
    }

    @Test
    fun onceCommitsToTheFirstAnswerWithoutExploringTheRest() {
        var explored = 0
        val search =
            choose(listOf(1, 2, 3)).map {
                explored++
                it * 10
            }
        assertEquals(listOf(10), once(search).collectAll().runOrThrow())
        assertEquals(1, explored)
    }

    @Test
    fun msplitReturnsTheFirstAnswerAndARunnableRestOfSearch() {
        val split = assertNotNull(choose(listOf(1, 2, 3)).map { it * 10 }.msplit().runOrThrow())
        assertEquals(10, split.first)
        assertEquals(listOf(20, 30), split.second.collectAll().runOrThrow())
    }

    @Test
    fun ifteRunsThenOverEveryAnswerElseOnlyWhenTheSearchIsEmpty() {
        fun factors(n: Int) = choose((2 until n).toList()).flatMap { d -> guard(n % d == 0).map { d } }

        val six = ifte(factors(6), { d -> Program.Done("d$d") }, Program.Done("prime")).collectAll().runOrThrow()
        assertEquals(listOf("d2", "d3"), six)

        val seven = ifte(factors(7), { d -> Program.Done("d$d") }, Program.Done("prime")).collectAll().runOrThrow()
        assertEquals(listOf("prime"), seven)
    }

    @Test
    fun multiShotResumptionOfProgramBlockSuspensionThrowsTheSingleShotError() {
        val e =
            assertFailsWith<IllegalStateException> {
                program { choose(listOf(1, 2)).bind() }.collectAll().runOrThrow()
            }
        assertContains(e.message.orEmpty(), "single-shot")
    }

    @Test
    fun localIsMultiShotSafe() {
        // Every branch sees the override, the outside does not
        val result =
            ask<String>()
                .flatMap { before ->
                    choose(listOf(1, 2))
                        .flatMap { n -> ask<String>().map { env -> "$env$n" } }
                        .local { e: String -> e.uppercase() }
                        .flatMap { inner -> ask<String>().map { after -> "$before|$inner|$after" } }
                }.runReader("e")
                .collectAll()
                .runOrThrow()
        assertEquals(listOf("e|E1|e", "e|E2|e"), result)
    }

    @Test
    fun transactionalIsMultiShotSafe() {
        // Each branch commits its own final state
        val result =
            choose(listOf(1, 2))
                .flatMap { n -> putState(n * 10) }
                .transactional()
                .flatMap { getState<Int>() }
                .runState(0)
                .collectAll()
                .runOrThrow()
        assertEquals(listOf(10, 20), result)
    }

    @Test
    fun middlewareOnMultiShotEffectBuiltWithInterpretAndPerformReturnsAllBranches() {
        val result =
            choose(listOf(1, 2, 3))
                .interpret<NonDet<*>, Int> { op, resume -> perform(op).flatMap { resume(it) } }
                .collectAll()
                .runOrThrow()
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun handleSRuleReturningProgramResponseWithoutSuspendingThrows() {
        assertMissingBind {
            perform(AskInt)
                .handleS<Ask<*>, Int, Int>(0) { s, _ -> s to Program.Done(5) }
                .runOrThrow()
        }
    }

    @Test
    fun interceptRuleReturningPerformOpInsteadOfProceedThrows() {
        assertMissingBind {
            perform(AskInt)
                .intercept<Ask<*>, Int> { op, _ -> perform(op) }
                .handle<Ask<*>, Int> { 1 }
                .runOrThrow()
        }
    }

    @Test
    fun handlerCallingShadowingPrintEffectConstructorThrows() {
        // The handler means kotlin.io.print but calls the print effect constructor
        fun print(msg: String) = perform(Say(msg))

        assertMissingBind {
            program { print("hi").bind() }
                .handle<Out<*>, Unit> { op ->
                    when (op) {
                        is Say -> print(op.s)
                    }
                }.runOrThrow()
        }
    }

    @Test
    fun onceOverLargeChoiceCommitsToTheFirstAnswer() {
        assertEquals(listOf(1), once(choose((1..200_000).toList())).collectAll().runOrThrow())
    }

    @Test
    fun msplitRestOverLargeChoiceCollectsEveryRemainingOptionInOrder() {
        val split = assertNotNull(choose((1..5_000).toList()).msplit().runOrThrow())
        assertEquals(1, split.first)
        assertEquals((2..5_000).toList(), split.second.collectAll().runOrThrow())
    }

    @Test
    fun msplitThenUnsplitKeepsTheDepthFirstAnswerOrderOfTwoLevelSearch() {
        val search = choose(listOf(1, 2, 3)).flatMap { x -> choose(listOf(10, 20)).map { x * it } }
        val direct = search.collectAll().runOrThrow()
        assertEquals(listOf(10, 20, 20, 40, 30, 60), direct)

        val resplit =
            search
                .msplit()
                .flatMap { s -> if (s == null) choose(emptyList()) else orElse(Program.Done(s.first), s.second) }
                .collectAll()
                .runOrThrow()
        assertEquals(direct, resplit)

        // Enumerating by repeatedly splitting the rest visits the same answers in the same order.
        val enumerated = mutableListOf<Int>()
        var rest: Program<Int> = search
        while (true) {
            val s = rest.msplit().runOrThrow() ?: break
            enumerated += s.first
            rest = s.second
        }
        assertEquals(direct, enumerated)
    }
}
