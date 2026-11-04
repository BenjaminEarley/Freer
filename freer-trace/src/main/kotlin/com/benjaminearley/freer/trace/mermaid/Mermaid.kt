package com.benjaminearley.freer.trace.mermaid

import com.benjaminearley.freer.trace.Call
import com.benjaminearley.freer.trace.Trace
import com.benjaminearley.freer.trace.Tree
import com.benjaminearley.freer.trace.describe
import com.benjaminearley.freer.trace.outcome
import java.util.IdentityHashMap

/**
 * The trace as a Mermaid sequence diagram. A handler that only passes an effect on is drawn as
 * a relay. A handler that does anything else with it (answers it, or performs effects of its own
 * before replying) is activated from the effect's arrival to its reply. An effect that never
 * gets a reply, because its handler ended the run instead, leaves that handler activated and is
 * marked with a note.
 */
public fun Trace.sequenceDiagram(): String =
    buildString {
        appendLine("sequenceDiagram")
        participants.forEach { appendLine("    participant $it") }
        var ended: Call? = null
        fun draw(call: Call) {
            val active = !call.relayed
            appendLine("    ${call.from}->>${if (active) "+" else ""}${call.to}: ${mermaidLabel(call.effect)}")
            call.inner.forEach(::draw)
            if (call.answered) {
                appendLine("    ${call.to}-->>${if (active) "-" else ""}${call.from}: ${mermaidLabel(call.response)}")
            } else if (call.inner.all { it.answered }) {
                ended = call
            }
        }
        calls().forEach(::draw)
        // The deepest call without a reply: the run ended in its handler.
        ended?.let { appendLine("    Note over ${it.to}: no reply#59; the run ended here") }
    }

/**
 * The tree as a Mermaid flowchart: effects as boxes, leaves as terminals, responses on the
 * edges, and a subtree that would be drawn exactly like one already drawn shares its node.
 */
public fun Tree.flowchart(): String {
    val drawn = IdentityHashMap<Tree, Any>()

    // What a subtree looks like once drawn: the key under which identical subtrees share a node.
    fun drawn(tree: Tree): Any =
        drawn.getOrPut(tree) {
            when (tree) {
                is Tree.Step -> mermaidLabel(tree.effect) to tree.branches.map { mermaidLabel(it.response) to drawn(it.subtree) }
                is Tree.Leaf -> tree.outcome()
            }
        }

    val ids = HashMap<Any, String>()
    val lines = mutableListOf("flowchart TD")
    fun draw(
        tree: Tree,
        id: String,
    ) {
        when (tree) {
            is Tree.Leaf -> lines += "    $id([\"${mermaidLabel(tree.outcome())}\"])"
            is Tree.Step -> {
                lines += "    $id[\"${mermaidLabel(tree.effect)}\"]"
                for (branch in tree.branches) {
                    val key = drawn(branch.subtree)
                    val known = ids[key]
                    val target = known ?: "n${ids.size}".also { ids[key] = it }
                    lines += if (branch.response == Unit) "    $id --> $target" else "    $id -- \"${mermaidLabel(branch.response)}\" --> $target"
                    if (known == null) draw(branch.subtree, target)
                }
            }
        }
    }
    ids[drawn(this)] = "n0"
    draw(this, "n0")
    return lines.joinToString("\n")
}

/**
 * [value] as text Mermaid shows as it is. Mermaid reads a label as syntax: a double quote ends
 * a quoted label, a semicolon ends a statement, a hash opens an entity code or a comment, angle
 * brackets open HTML, a backtick opening a label opens a Markdown string, and a pair of percent
 * signs opens a directive. Each becomes the entity code Mermaid turns back into the character
 * when it renders, and a line break becomes the tag Mermaid breaks lines on. An empty label is
 * a syntax error, so it becomes a space.
 */
public fun mermaidLabel(value: Any?): String {
    val text = if (value == Unit) "Unit" else describe(value)
    val escaped =
        buildString(text.length) {
            for (c in text) {
                when (c) {
                    '#' -> append("#35;")
                    '"' -> append("#quot;")
                    ';' -> append("#59;")
                    '<' -> append("#lt;")
                    '>' -> append("#gt;")
                    '`' -> append("#96;")
                    '%' -> append("#37;")
                    '\n' -> append("<br/>")
                    '\r' -> {}
                    else -> append(c)
                }
            }
        }
    return escaped.ifEmpty { " " }
}
