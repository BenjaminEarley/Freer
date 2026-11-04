package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayTest {
    private val flow: Program<String> =
        program {
            val n = perform(AskCount).bind()
            perform(Ping).bind()
            if (perform(AskFlag).bind()) "flag with $n" else "no flag"
        }

    private fun <A> Program<A>.answers(): Program<A> =
        handle<Probe<*>, A> { op ->
            when (op) {
                AskFlag -> true
                AskCount -> 7
                Ping -> Unit
                else -> error("unexpected $op")
            }
        }

    private fun record(program: Program<*>): Trace {
        val trace = Trace(last = "attempt")
        program.observed("program", trace).answers().observed("answers", trace).attempt().runOrThrow()
        return trace
    }

    @Test
    fun aReplayComputesTheResultFromTheRecordedResponses() {
        val replayed = flow.replay(record(flow))
        assertEquals("flag with 7", replayed.runOrThrow())
        assertEquals("flag with 7", replayed.runOrThrow(), "a replayed program is reusable")
    }

    @Test
    fun theFirstDivergentEffectFails() {
        val other =
            program {
                perform(Ping).bind()
                perform(AskCount).bind()
                "other"
            }
        val failure = assertFailsWith<IllegalStateException> { other.replay(record(flow)).runOrThrow() }
        assertEquals("the program performed Ping at step 1 where the recording has AskCount", failure.message)
    }

    @Test
    fun finishingEarlyOrGoingOnPastTheRecordingFails() {
        val trace = record(flow)
        val early = assertFailsWith<IllegalStateException> { perform(AskCount).map { "done" }.replay(trace).runOrThrow() }
        assertEquals("the program finished after 1 of 3 recorded effects, before Ping", early.message)
        val late = assertFailsWith<IllegalStateException> { flow.flatMap { result -> perform(Ping).map { result } }.replay(trace).runOrThrow() }
        assertEquals("the program performed Ping at step 4, which the recording has no answer for", late.message)
    }

    @Test
    fun anUnansweredEffectIsPassedOn() {
        val failing = perform(AskFlag).flatMap { flag -> if (flag) raise("boom") else Program.Done("quiet") }
        val trace = record(failing)
        assertEquals("boom", failing.replay(trace).attempt().runOrThrow().exceptionOrNull()?.message)
        val unhandled = assertFailsWith<IllegalStateException> { failing.replay(trace).runOrThrow() }
        assertTrue("Unhandled effect" in unhandled.message.orEmpty(), unhandled.message)
        val quiet = assertFailsWith<IllegalStateException> { perform(AskFlag).map { "quiet" }.replay(trace).runOrThrow() }
        assertEquals("the program finished where the recording performed Raise(boom)", quiet.message)
    }

    @Test
    fun aPathReplaysAndChecksTheValue() {
        val path = flow.tree { ofType<Int>(7) }.paths().single { it.end == Tree.Returns("flag with 7") }
        assertEquals("flag with 7", flow.replay(path).runOrThrow())
        val other =
            program {
                perform(AskCount).bind()
                perform(Ping).bind()
                perform(AskFlag).bind()
                "other"
            }
        val failure = assertFailsWith<IllegalStateException> { other.replay(path).runOrThrow() }
        assertEquals("the program returned other where the recording returned flag with 7", failure.message)
        val failing = perform(AskFlag).flatMap { flag -> if (flag) raise("boom") else Program.Done("quiet") }
        val stop = failing.tree().paths().single { it.end is Tree.Stops }
        assertEquals("boom", failing.replay(stop).attempt().runOrThrow().exceptionOrNull()?.message)
    }
}
