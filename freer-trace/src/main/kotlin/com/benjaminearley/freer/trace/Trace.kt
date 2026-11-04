package com.benjaminearley.freer.trace

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.intercept

/** What crossed one wire of a handler stack: an effect on its way down, or its response on the way up. */
public sealed interface Wire {
    /** The observer that saw it, named after the participant above the wire. */
    public val layer: String

    public val effect: Effect<*>

    public data class Down(
        override val layer: String,
        override val effect: Effect<*>,
    ) : Wire

    public data class Up(
        override val layer: String,
        override val effect: Effect<*>,
        val response: Any?,
    ) : Wire
}

/**
 * The operational trace of one run under one handler stack: what each layer asked the layer
 * below it and what came back. A program is a value whose effects are answered by whichever
 * handlers are applied to it, so the trace shows what one stack did with it on one run, not
 * what the program could do; the latter is its [Tree].
 *
 * Observers placed between the handlers with [observed] record the trace, so it costs the run
 * nothing but a log. The participants are the observers' layers in the order they were applied,
 * then [last], the handler below the last observer. [calls] gives the trace as nested calls,
 * which is the shape a renderer wants.
 */
public class Trace(
    /** The participant below the last observer: the handler applied last, or whatever runs the program. */
    public val last: String,
) {
    private val layerNames = mutableListOf<String>()
    private val wireLog = mutableListOf<Wire>()

    init {
        require(last.matches(PARTICIPANT)) { "a participant name must be an identifier, not \"$last\"" }
    }

    /** The observers' layers, in the order the observers were applied. */
    public val layers: List<String> get() = layerNames

    /** Every wire crossing, in order. */
    public val wires: List<Wire> get() = wireLog

    /** The layers, then [last]. */
    public val participants: List<String> get() = layerNames + last

    internal fun addLayer(layer: String) {
        require(layer.matches(PARTICIPANT)) { "a participant name must be an identifier, not \"$layer\"" }
        require(layer !in layerNames) { "a participant named $layer is already in the trace" }
        layerNames += layer
    }

    internal fun record(wire: Wire) {
        wireLog += wire
    }

    /**
     * The trace as calls: an effect's stay in one participant, from the wire that brought it in
     * to the wire that took its reply out, with the calls the participant made meanwhile nested
     * inside. The top-level calls are those the program made. A call that never got a reply,
     * because its handler ended the run, is closed unanswered with its inner calls intact.
     */
    public fun calls(): List<Call> {
        val names = participants

        class Open(
            val from: String,
            val to: String,
            val effect: Effect<*>,
        ) {
            val inner = mutableListOf<Call>()
        }

        val top = mutableListOf<Call>()
        val stack = ArrayDeque<Open>()
        fun close(
            open: Open,
            answered: Boolean,
            response: Any?,
        ) {
            val call = Call(open.from, open.to, open.effect, answered, response, open.inner.toList())
            (stack.lastOrNull()?.inner ?: top) += call
        }
        for ((index, wire) in wireLog.withIndex()) {
            val layer = layerNames.indexOf(wire.layer)
            check(layer >= 0) { "wire $index crossed an unknown layer ${wire.layer}" }
            when (wire) {
                is Wire.Down -> stack.addLast(Open(names[layer], names[layer + 1], wire.effect))
                is Wire.Up -> {
                    val open = stack.removeLastOrNull()
                    check(open != null && open.from == names[layer] && open.effect == wire.effect) { "wire $index replies to no open call" }
                    close(open, answered = true, response = wire.response)
                }
            }
        }
        while (stack.isNotEmpty()) close(stack.removeLast(), answered = false, response = null)
        return top
    }

    /** The receiver of [parse]. */
    public companion object
}

/** An effect's stay in one participant of a [Trace]. */
public class Call internal constructor(
    /** The participant the effect came from. */
    public val from: String,
    /** The participant that received it. */
    public val to: String,
    public val effect: Effect<*>,
    /** Whether [to] replied; a handler that ends the run replies to nothing. */
    public val answered: Boolean,
    /** The reply, when [answered]. */
    public val response: Any?,
    /** The calls [to] made while it held the effect, in order: a relay of the effect itself, or effects of its own. */
    public val inner: List<Call>,
) {
    /** Whether [to] only passed the effect on: its one inner call is the same effect, and the reply, if any, came straight back. */
    public val relayed: Boolean
        get() = inner.size == 1 && inner[0].effect == effect && inner[0].answered == answered

    override fun toString(): String = "Call($from -> $to: ${describe(effect)}${if (answered) " = ${describe(response)}" else ", unanswered"})"
}

private val PARTICIPANT = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Observe the wire between this program (or handler) and the next handler applied to it, as the
 * participant named [layer] of [trace]. An observer over the root family `Effect<*>` sees every
 * effect of every family, so the same observer fits between any two layers.
 */
public fun <A> Program<A>.observed(
    layer: String,
    trace: Trace,
): Program<A> {
    trace.addLayer(layer)
    return intercept<Effect<*>, A> { op, proceed ->
        trace.record(Wire.Down(layer, op))
        val response = proceed()
        trace.record(Wire.Up(layer, op, response))
        response
    }
}
