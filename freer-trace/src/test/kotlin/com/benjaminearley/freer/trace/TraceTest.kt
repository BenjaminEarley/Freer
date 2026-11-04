package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.trace.mermaid.flowchart
import com.benjaminearley.freer.trace.mermaid.mermaidLabel
import com.benjaminearley.freer.trace.mermaid.sequenceDiagram
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.laws.LAW_ITERATIONS
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.runOrThrow
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private val ENTITY = Regex("#(35|quot|59|lt|gt|96|37);")

// Undoes mermaidLabel the way Mermaid does when it renders.
private fun decode(label: String): String =
    label.replace("<br/>", "\n").replace(ENTITY) { entity ->
        when (entity.groupValues[1]) {
            "35" -> "#"
            "quot" -> "\""
            "59" -> ";"
            "lt" -> "<"
            "gt" -> ">"
            "96" -> "`"
            else -> "%"
        }
    }

class TraceTest {
    @Test
    fun theSequenceDiagramActivatesTheHandlerThatDoesTheWork() {
        val trace = Trace(last = "attempt")
        val flow = perform(AskFlag).flatMap { flag -> if (flag) raise("boom") else Program.Done("quiet") }
        flow
            .observed("program", trace)
            .intercept<Probe<*>, String> { _, proceed ->
                perform(Ping).bind()
                proceed()
            }.observed("pinger", trace)
            .intercept<Probe<*>, String> { _, proceed -> proceed() }
            .observed("passthrough", trace)
            .handle<Probe<*>, String> { op -> if (op == Ping) Unit else true }
            .observed("answers", trace)
            .attempt()
            .runOrThrow()

        val calls = trace.calls()
        assertEquals(listOf("program -> pinger: AskFlag = true", "program -> pinger: Raise(boom), unanswered"), calls.map { it.toString().removeSurrounding("Call(", ")") })
        assertFalse(calls[0].relayed, "the middleware pinged before passing the effect on")
        assertEquals(listOf(Ping, AskFlag), calls[0].inner.map { it.effect })
        assertTrue(calls[0].inner[1].relayed && !calls[0].inner[1].inner.single().relayed, "passed on, then answered")
        val descent = generateSequence(calls[1]) { it.inner.singleOrNull() }.toList()
        assertEquals(listOf("pinger", "passthrough", "answers", "attempt"), descent.map { it.to }, "the failure went all the way down")
        assertEquals(listOf(true, true, true, false), descent.map { it.relayed }, "relayed until the handler that ended the run")

        val diagram = trace.sequenceDiagram()
        assertEquals(
            """
            sequenceDiagram
                participant program
                participant pinger
                participant passthrough
                participant answers
                participant attempt
                program->>+pinger: AskFlag
                pinger->>passthrough: Ping
                passthrough->>+answers: Ping
                answers-->>-passthrough: Unit
                passthrough-->>pinger: Unit
                pinger->>passthrough: AskFlag
                passthrough->>+answers: AskFlag
                answers-->>-passthrough: true
                passthrough-->>pinger: true
                pinger-->>-program: true
                program->>pinger: Raise(boom)
                pinger->>passthrough: Raise(boom)
                passthrough->>answers: Raise(boom)
                answers->>+attempt: Raise(boom)
                Note over attempt: no reply#59; the run ended here

            """.trimIndent(),
            diagram,
        )
        recordDiagram("the working handler activated, a failure never answered", diagram)
    }

    @Test
    fun theAdversarialNamesEscapeAsDocumented() {
        assertEquals(
            "say #quot;hi#quot;#59; #35;1 #lt;b#gt;<br/>#37;#37;{init}#37;#37; #96;md#96;",
            mermaidLabel("say \"hi\"; #1 <b>\n%%{init}%% `md`"),
        )
        assertEquals(" ", mermaidLabel(""))
        assertEquals("Unit", mermaidLabel(Unit))
    }

    @Test
    fun labelsEscapeEverythingMermaidReadsAsSyntax() =
        runTest(timeout = 10.minutes) {
            val arbText = Arb.list(Arb.of("ab1 \"#;<>`%\n\r\\|[](){}:,-.~=&'".toList()), 0..24).map { it.joinToString("") }
            var recorded = 0
            checkAll(LAW_ITERATIONS, arbText, arbText) { name, response ->
                val label = mermaidLabel(name)
                // Outside the line breaks and the entity codes, nothing Mermaid reads as syntax survives.
                val outside = ENTITY.replace(label.replace("<br/>", ""), "")
                for (c in "\"#<>`%;\n\r") assertFalse(c in outside, "$c survived in $label")
                assertEquals(name.replace("\r", "").ifEmpty { " " }, decode(label))
                // A diagram of the texts as an effect and its response has exactly the lines it
                // should: nothing in them became a statement of its own.
                val effect = Named(name)
                val flowchart = Tree.Step(effect, listOf(Branch(response, Tree.Returns(name)))).flowchart()
                assertEquals(4, flowchart.lines().size, flowchart)
                val trace = Trace(last = "handler")
                perform(effect).observed("program", trace).handle<Probe<*>, Any?> { response }.runOrThrow()
                val sequence = trace.sequenceDiagram()
                assertEquals(5, sequence.trimEnd().lines().size, sequence)
                if (recorded++ < 40) {
                    recordDiagram("adversarial flowchart $recorded", flowchart)
                    recordDiagram("adversarial sequence $recorded", sequence)
                }
            }
        }
}
