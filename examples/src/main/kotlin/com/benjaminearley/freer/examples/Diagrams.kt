/*
 * The accounting program drawn two ways with freer-trace: one run under the production
 * stack as a sequence diagram, and every path as a flowchart. The scenario prints both, and
 * `./gradlew :examples:updateDiagrams` writes them to README.md of this module as a page.
 */
package com.benjaminearley.freer.examples

import com.benjaminearley.freer.trace.Answer
import com.benjaminearley.freer.trace.Trace
import com.benjaminearley.freer.trace.describe
import com.benjaminearley.freer.trace.mermaid.flowchart
import com.benjaminearley.freer.trace.mermaid.sequenceDiagram
import com.benjaminearley.freer.trace.observed
import com.benjaminearley.freer.trace.redundantResponses
import com.benjaminearley.freer.trace.replay
import com.benjaminearley.freer.trace.tree
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.examples.paymentRefund.effects.auditFraudCheck
import com.benjaminearley.freer.examples.paymentRefund.effects.auditKVStore
import com.benjaminearley.freer.examples.paymentRefund.effects.fraudCheck
import com.benjaminearley.freer.examples.paymentRefund.effects.kvStore
import com.benjaminearley.freer.examples.paymentRefund.effects.logger
import com.benjaminearley.freer.examples.paymentRefund.transferMoney
import com.benjaminearley.freer.runOrThrow

private class Diagrams(
    val trace: Trace,
    val sequence: String,
    val flowchart: String,
    val redundant: List<Answer>,
)

private const val SEQUENCE_CAPTION = "A run of transferMoney(\"Alice\", \"Bob\", 6000.0) under the production stack, as a sequence diagram"
private const val FLOWCHART_CAPTION = "Every path of transferMoney(\"Alice\", \"Bob\", 100.0), as a flowchart"

/** The balances a read may answer with in the flowchart: one short of the amount and one well over it. */
private val BALANCES = listOf(50.0, 1000.0)

private fun diagrams(): Diagrams {
    val trace = Trace(last = "attempt")
    val database = mutableMapOf("Alice" to 10000.0, "Bob" to 50.0)
    transferMoney("Alice", "Bob", 6000.0)
        .observed("program", trace)
        .auditKVStore()
        .observed("auditKVStore", trace)
        .auditFraudCheck()
        .observed("auditFraudCheck", trace)
        .kvStore(database)
        .observed("kvStore", trace)
        .fraudCheck()
        .observed("fraudCheck", trace)
        .logger()
        .observed("logger", trace)
        .attempt()
        .runOrThrow()
    // A read's response type is its type parameter, witnessed by its default: a Double.
    val tree = transferMoney("Alice", "Bob", 100.0).tree { ofType(Double::class, BALANCES) }
    return Diagrams(trace, trace.sequenceDiagram(), tree.flowchart(), tree.redundantResponses())
}

private fun redundancyReport(redundant: List<Answer>): String =
    if (redundant.isEmpty()) {
        "Every representative opened a path of its own."
    } else {
        "A representative that opened no path of its own, every effect and outcome below it being reachable through a sibling: " +
            redundant.joinToString { "`${describe(it.effect)}` answered `${describe(it.response)}`" } + "."
    }

fun diagramsExample() {
    val diagrams = diagrams()
    println("\n$SEQUENCE_CAPTION:\n")
    println(diagrams.sequence)
    println("$FLOWCHART_CAPTION:\n")
    println(diagrams.flowchart)
    println()
    println(redundancyReport(diagrams.redundant))
    // The recorded run again, with no handler but the trace: it answers every effect the program
    // performed, and the failure it left unanswered goes on to attempt() as before.
    val replayed = transferMoney("Alice", "Bob", 6000.0).replay(diagrams.trace).attempt().runOrThrow()
    println("Replayed from the trace, without the handlers: $replayed")
}

/** The diagrams as a Markdown page, which GitHub renders; `./gradlew :examples:updateDiagrams` writes it to README.md. */
fun diagramsMarkdown(): String {
    val diagrams = diagrams()
    return """
        |# Examples
        |
        |`./gradlew :examples:run` runs every scenario of the demo in sequence, from `src/main/kotlin/com/benjaminearley/freer/examples/Main.kt`: accounting with audit middleware, async I/O and error handling, bind-chain performance, error recovery, handler ordering, generators, nondeterminism, transactions, reader overrides, committed choice, and the diagrams below.
        |
        |The diagrams are the output of the last scenario, `Diagrams.kt`, which draws the accounting program two ways with the `freer-trace` module. A program is a value: a tree whose nodes are the effects it performs and whose edges are the responses it may receive. The handlers applied to it decide what each effect does, so a diagram is one more interpretation of the value, and the two below denote different things. This page is generated; `check` fails when it is stale, and `./gradlew :examples:updateDiagrams` regenerates it. CI parses both diagrams with Mermaid's own parser.
        |
        |## One run as a sequence diagram
        |
        |$SEQUENCE_CAPTION: the operational trace of one run under one handler stack, recorded by an observer between every two handlers. Each effect passes down from the program to the handler that answers it, and its response comes back up. A handler that only passes an effect on is drawn as a relay; one that answers it, or performs effects of its own before replying, is activated until its reply. The fraud verdict comes back up through the audit middleware, which logs a warning of its own into the stack below it before replying. The failure reaches the last handler and never gets a reply, because that handler ends the run.
        |
        |```mermaid
        |${diagrams.sequence.trimEnd()}
        |```
        |
        |## Every path as a flowchart
        |
        |$FLOWCHART_CAPTION: the program's tree, unfolded under every response of each effect. Boolean, enum, sealed-object and Unit responses are enumerated from the effect's response type. A balance read answers with its type parameter, which its default value witnesses as a `Double`; that has no finite set of values, so the read is unfolded under two representatives, ${BALANCES.joinToString(
        " and ",
    )}. A failure has no response, so it ends its path. No handler is applied: a stand-in answers each effect and the program is replayed once per path. Identical subtrees are drawn once. ${redundancyReport(
        diagrams.redundant,
    )}
        |
        |```mermaid
        |${diagrams.flowchart}
        |```
        |
        """.trimMargin()
}

fun main(args: Array<String>) {
    val page = java.io.File(args.single())
    page.parentFile.mkdirs()
    page.writeText(diagramsMarkdown())
}
