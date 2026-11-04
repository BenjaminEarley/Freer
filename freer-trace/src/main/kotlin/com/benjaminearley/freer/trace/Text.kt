package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect

/**
 * A stand-in, in a trace read from text, for an effect or a response the text only describes:
 * the simple name of its class and its description. It draws and compares as the original did.
 * It cannot be handed to a program, so a response read from text replays only when the text
 * kept it typed: null, Unit, a Boolean, an Int, a Long, a Float, a Double or a String.
 */
public class Described(
    public val className: String,
    public val text: String,
) : Effect<Any?> {
    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is Described && other.className == className && other.text == text

    override fun hashCode(): Int = 31 * className.hashCode() + text.hashCode()
}

private const val FORMAT = "freer-trace 1"
private val WIRE = Regex("""([A-Za-z_][A-Za-z0-9_]*) (->|<-) (.*)""")

/**
 * The trace as text, one wire per line, for a golden file or for [Trace.parse]. After a header
 * naming the format, the last participant and the layers, an effect on its way down is
 * `layer -> Class: description` and a response on its way up is `layer <- literal`, where a
 * literal is `null`, `kotlin.Unit`, a Boolean, a number (a Long with an `L`, a Float with an
 * `f`), a quoted String, or `{Class} description` for anything else. Backslashes, line breaks
 * and, in a String, double quotes are escaped with a backslash, so every wire is one line.
 */
public fun Trace.toText(): String =
    buildString {
        appendLine(FORMAT)
        appendLine("last: $last")
        appendLine("layers: ${layers.joinToString(" ")}")
        for (wire in wires) {
            when (wire) {
                is Wire.Down -> appendLine("${wire.layer} -> ${className(wire.effect)}: ${escape(describe(wire.effect))}")
                is Wire.Up -> appendLine("${wire.layer} <- ${literal(wire.response)}")
            }
        }
    }

/**
 * The trace [toText] wrote. Its effects are [Described] stand-ins, and its responses are the
 * values the literals keep typed, or stand-ins too.
 */
public fun Trace.Companion.parse(text: String): Trace {
    val lines = text.lines().filter { it.isNotEmpty() }
    require(lines.firstOrNull() == FORMAT) { "not a trace: the first line should be \"$FORMAT\"" }
    val last = lines.getOrNull(1)?.takeIf { it.startsWith("last: ") }?.removePrefix("last: ") ?: error("line 2 should name the last participant")
    val layers = lines.getOrNull(2)?.takeIf { it.startsWith("layers:") }?.removePrefix("layers:") ?: error("line 3 should list the layers")
    val trace = Trace(last)
    layers.split(" ").filter { it.isNotEmpty() }.forEach(trace::addLayer)
    val open = HashMap<String, ArrayDeque<Described>>()
    for ((index, line) in lines.drop(3).withIndex()) {
        val number = index + 4
        val (layer, direction, rest) = WIRE.matchEntire(line)?.destructured ?: error("line $number is not a wire: $line")
        if (direction == "->") {
            val colon = rest.indexOf(": ")
            require(colon > 0) { "line $number names no effect class: $line" }
            val effect = Described(rest.substring(0, colon), unescape(rest.substring(colon + 2)))
            open.getOrPut(layer) { ArrayDeque() }.addLast(effect)
            trace.record(Wire.Down(layer, effect))
        } else {
            val effect = open[layer]?.removeLastOrNull() ?: error("line $number replies on $layer, which has no open effect: $line")
            trace.record(Wire.Up(layer, effect, literal(rest, number)))
        }
    }
    return trace
}

private fun className(effect: Effect<*>): String = if (effect is Described) effect.className else effect::class.simpleName ?: effect::class.java.name

private fun literal(value: Any?): String =
    when (value) {
        null -> "null"
        Unit -> "kotlin.Unit"
        is Boolean, is Int, is Double -> value.toString()
        is Long -> "${value}L"
        is Float -> "${value}f"
        is String -> "\"${escape(value).replace("\"", "\\\"")}\""
        is Described -> "{${value.className}} ${escape(value.text)}"
        else -> "{${value::class.simpleName ?: value::class.java.name}} ${escape(describe(value))}"
    }

private fun literal(
    text: String,
    line: Int,
): Any? =
    when {
        text == "null" -> null
        text == "kotlin.Unit" -> Unit
        text == "true" -> true
        text == "false" -> false
        text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") -> unescape(text.substring(1, text.length - 1))
        text.startsWith("{") -> {
            val end = text.indexOf("} ")
            require(end > 1) { "line $line names no class: $text" }
            Described(text.substring(1, end), unescape(text.substring(end + 2)))
        }

        else ->
            runCatching {
                when {
                    text.endsWith("L") -> text.dropLast(1).toLong()
                    text.endsWith("f") -> text.dropLast(1).toFloat()
                    else -> text.toIntOrNull() ?: text.toDouble()
                }
            }.getOrElse { error("line $line has no literal: $text") }
    }

private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private fun unescape(text: String): String =
    buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
