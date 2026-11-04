package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.laws.LAW_ITERATIONS
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow
import com.benjaminearley.freer.trace.mermaid.sequenceDiagram
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TextTest {
    private fun record(
        program: Program<*>,
        answer: (Effect<*>) -> Any?,
    ): Trace {
        val trace = Trace(last = "run")
        // Two observers above the handler, which is the last participant.
        program.observed("program", trace).observed("answers", trace).handle<Probe<*>, Any?> { answer(it) }.runOrThrow()
        return trace
    }

    @Test
    fun theTextHasOneLinePerWire() {
        val flow =
            program {
                perform(AskCount).bind()
                perform(Named("say \"hi\"\nnow")).bind()
            }
        assertEquals(
            """
            freer-trace 1
            last: run
            layers: program answers
            program -> AskCount: AskCount
            answers -> AskCount: AskCount
            answers <- 7
            program <- 7
            program -> Named: say "hi"\nnow
            answers -> Named: say "hi"\nnow
            answers <- "ok"
            program <- "ok"

            """.trimIndent(),
            record(flow) { if (it == AskCount) 7 else "ok" }.toText(),
        )
    }

    @Test
    fun aTraceRoundTripsThroughTextWithItsLiteralsTyped() {
        val values = listOf<Any?>(null, Unit, true, 5, 5L, 1.5, 2.5f, "a\"b\\c\nd", Light.RED, Plain(1, "x"))
        val flow =
            program {
                for (i in values.indices) perform(Named("r$i")).bind()
            }
        val trace = record(flow) { values[(it as Named).name.drop(1).toInt()] }
        val text = trace.toText()
        val parsed = Trace.parse(text)
        assertEquals(text, parsed.toText())
        assertEquals(trace.sequenceDiagram(), parsed.sequenceDiagram())
        assertEquals(trace.calls().map { it.toString() }, parsed.calls().map { it.toString() })
        val responses = parsed.wires.filterIsInstance<Wire.Up>().filter { it.layer == "answers" }.map { it.response }
        assertNull(responses[0])
        assertEquals(Unit, responses[1])
        assertEquals(true, responses[2])
        assertEquals(5, assertIs<Int>(responses[3]))
        assertEquals(5L, assertIs<Long>(responses[4]))
        assertEquals(1.5, assertIs<Double>(responses[5]))
        assertEquals(2.5f, assertIs<Float>(responses[6]))
        assertEquals("a\"b\\c\nd", assertIs<String>(responses[7]))
        assertEquals(Described("Light", "RED"), responses[8])
        assertEquals(Described("Plain", "Plain(n=1, s=x)"), responses[9])
        assertEquals(Described("Named", "r0"), parsed.wires.first().effect)
    }

    @Test
    fun aParsedTraceReplaysLiteralsAndRefusesADescribedResponse() {
        val flow =
            program {
                val n = perform(AskCount).bind()
                val s = perform(Named("s")).bind() as String
                "$n $s"
            }
        val parsed = Trace.parse(record(flow) { if (it == AskCount) 7 else "ok" }.toText())
        assertEquals("7 ok", flow.replay(parsed).runOrThrow())
        val lit = perform(AskLight)
        val failure = assertFailsWith<IllegalStateException> { lit.replay(Trace.parse(record(lit) { Light.RED }.toText())).runOrThrow() }
        assertEquals("the recording only describes the response to AskLight at step 1: RED", failure.message)
    }

    @Test
    fun textThatIsNotATraceIsRejected() {
        assertTrue("not a trace" in assertFailsWith<IllegalArgumentException> { Trace.parse("hello") }.message.orEmpty())
        val header = "freer-trace 1\nlast: run\nlayers: program\n"
        assertTrue("not a wire" in assertFailsWith<IllegalStateException> { Trace.parse(header + "program => x\n") }.message.orEmpty())
        assertTrue("no open effect" in assertFailsWith<IllegalStateException> { Trace.parse(header + "program <- 1\n") }.message.orEmpty())
        assertTrue("no literal" in assertFailsWith<IllegalStateException> { Trace.parse(header + "program -> A: a\nprogram <- 1x\n") }.message.orEmpty())
    }

    @Test
    fun anyTextInAnEffectOrAResponseRoundTrips() =
        runTest(timeout = 10.minutes) {
            val arbText = Arb.list(Arb.of("ab \"\\\n\r->:{}<-\t".toList()), 0..16).map { it.joinToString("") }
            checkAll(LAW_ITERATIONS, arbText, arbText) { name, response ->
                val trace = record(perform(Named(name))) { response }
                val text = trace.toText()
                val parsed = Trace.parse(text)
                assertEquals(text, parsed.toText(), text)
                assertEquals(Described("Named", name), parsed.wires.first().effect)
                assertEquals(response, (parsed.wires.last() as Wire.Up).response)
                assertEquals(trace.sequenceDiagram(), parsed.sequenceDiagram())
            }
        }
}
