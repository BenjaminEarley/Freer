/*
 * Random handler stacks for the law tests. A stack is a list of handler applications, each
 * known in both worlds, that together handle every effect family a program may use; some
 * entries are wrappers such as local or middleware, which must come before the handler of
 * the family they rely on.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.Reader
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.local
import com.benjaminearley.freer.effects.msplit
import com.benjaminearley.freer.effects.once
import com.benjaminearley.freer.effects.recover
import com.benjaminearley.freer.effects.runReader
import com.benjaminearley.freer.effects.runState
import com.benjaminearley.freer.effects.runStateWithFinal
import com.benjaminearley.freer.effects.transactional
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.map
import com.benjaminearley.freer.reference.Ref
import com.benjaminearley.freer.reference.attempt
import com.benjaminearley.freer.reference.collectAll
import com.benjaminearley.freer.reference.flatMap
import com.benjaminearley.freer.reference.interpose
import com.benjaminearley.freer.reference.local
import com.benjaminearley.freer.reference.map
import com.benjaminearley.freer.reference.msplit
import com.benjaminearley.freer.reference.once
import com.benjaminearley.freer.reference.pure
import com.benjaminearley.freer.reference.recover
import com.benjaminearley.freer.reference.runReader
import com.benjaminearley.freer.reference.runState
import com.benjaminearley.freer.reference.runStateWithFinal
import com.benjaminearley.freer.reference.send
import com.benjaminearley.freer.reference.transactional
import com.benjaminearley.freer.toEither
import io.kotest.property.Arb
import io.kotest.property.Shrinker
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

enum class Family { TICK, STATE, READER, NONDET, FAILURE }

/**
 * One handler application. [handles] is the family it removes, null for a wrapper; [needs]
 * is the family a wrapper relies on downstream. [readOnly] marks applications that neither
 * keep state nor cut a program short, which some laws depend on.
 */
class Handler(
    val name: String,
    val handles: Family?,
    val needs: Family? = null,
    val readOnly: Boolean,
    val lib: (Program<Any?>) -> Program<Any?>,
    val ref: (Ref<Any?>) -> Ref<Any?>,
) {
    override fun toString(): String = name
}

/** Every answer, found by splitting off one answer at a time. */
fun <A> Program<A>.enumerateBySplits(): Program<List<A>> =
    msplit().flatMap { split ->
        if (split == null) Program.Done(emptyList()) else split.second.enumerateBySplits().map { listOf(split.first) + it }
    }

fun <A> Ref<A>.enumerateBySplits(): Ref<List<A>> =
    msplit().flatMap { split ->
        if (split == null) pure(emptyList()) else split.second.enumerateBySplits().map { listOf(split.first) + it }
    }

val countTicksHandler = Handler("countTicks", Family.TICK, readOnly = false, lib = { it.countTicks() }, ref = { it.countTicks() })

val countTicksByFunctionsHandler =
    Handler("countTicksByFunctions", Family.TICK, readOnly = false, lib = { it.countTicksByFunctions() }, ref = { it.countTicks() })

private fun Random.familyHandler(family: Family): Handler =
    when (family) {
        Family.TICK ->
            when (nextInt(5)) {
                0 -> countTicksHandler
                1 -> countTicksByFunctionsHandler
                2 ->
                    Handler("countTicksQuietly", family, readOnly = false, lib = { it.countTicksQuietly() }, ref = {
                        it.countTicks().map { counted -> counted.first }
                    })

                3 -> nextInt(-2, 3).let { n -> Handler("constantTicks($n)", family, readOnly = true, lib = { it.constantTicks(n) }, ref = { it.constantTicks(n) }) }
                else ->
                    nextInt(-2, 3).let { n ->
                        Handler("constantTicksDirect($n)", family, readOnly = true, lib = { it.constantTicksDirect(n) }, ref = { it.constantTicks(n) })
                    }
            }

        Family.STATE ->
            nextInt(-2, 3).let { s ->
                if (nextBoolean()) {
                    Handler("runState($s)", family, readOnly = false, lib = { it.runState(s) }, ref = { it.runState(s) })
                } else {
                    Handler("runStateWithFinal($s)", family, readOnly = false, lib = { it.runStateWithFinal(s) }, ref = { it.runStateWithFinal(s) })
                }
            }

        Family.READER -> nextInt(-2, 3).let { e -> Handler("runReader($e)", family, readOnly = true, lib = { it.runReader(e) }, ref = { it.runReader(e) }) }

        Family.NONDET ->
            when (nextInt(3)) {
                0 -> Handler("collectAll", family, readOnly = false, lib = { it.collectAll() }, ref = { it.collectAll() })
                1 -> Handler("once then collectAll", family, readOnly = false, lib = { once(it).collectAll() }, ref = { once(it).collectAll() })
                else -> Handler("enumerateBySplits", family, readOnly = false, lib = { it.enumerateBySplits() }, ref = { it.enumerateBySplits() })
            }

        Family.FAILURE ->
            if (nextBoolean()) {
                Handler("attempt", family, readOnly = false, lib = { it.attempt().toEither() }, ref = { it.attempt() })
            } else {
                Handler("recover(length)", family, readOnly = false, lib = {
                    it.recover { reason -> Program.Done(reason.length) }
                }, ref = { it.recover { reason -> pure(reason.length) } })
            }
    }

private fun Random.wrapper(): Handler =
    when (nextInt(4)) {
        0 -> Handler("local(+1)", null, Family.READER, readOnly = true, lib = { it.local { e: Int -> e + 1 } }, ref = { it.local { e: Int -> e + 1 } })
        1 -> Handler("transactional", null, Family.STATE, readOnly = false, lib = { it.transactional() }, ref = { it.transactional() })
        2 -> Handler("shiftTicks(+100)", null, Family.TICK, readOnly = true, lib = { it.shiftTicks(100) }, ref = { it.shiftTicks(100) })
        else ->
            Handler("doubleAsk", null, Family.READER, readOnly = true, lib = {
                it.intercept<Reader<*>, Any?> { _, proceed -> (proceed() as Int) * 2 }
            }, ref = {
                it.interpose<Reader<*>, Any?, Any?>({ pure(it) }) { op, resume -> send(op).flatMap { answer -> resume((answer as Int) * 2) } }
            })
    }

/** Handler applications in order: the first is applied to the program first, so it is innermost. */
class Stack(
    val handlers: List<Handler>,
) {
    fun applyLib(program: Program<*>): Program<Any?> = handlers.fold(program as Program<Any?>) { acc, handler -> handler.lib(acc) }

    fun applyRef(program: Ref<*>): Ref<Any?> = handlers.fold(program as Ref<Any?>) { acc, handler -> handler.ref(acc) }

    fun insert(
        at: Int,
        handler: Handler,
    ): Stack = Stack(handlers.toMutableList().apply { add(at, handler) })

    /** The index of the handler of [family]. */
    fun indexOf(family: Family): Int = handlers.indexOfFirst { it.handles == family }

    override fun toString(): String = handlers.joinToString(" then ")
}

private fun Random.stack(families: Set<Family>): Stack {
    val handlers = families.shuffled(this).map { familyHandler(it) }.toMutableList()
    repeat(nextInt(0, 3)) {
        val wrapper = wrapper()
        val limit = handlers.indexOfFirst { it.handles == wrapper.needs }
        if (limit >= 0) handlers.add(nextInt(0, limit + 1), wrapper)
    }
    return Stack(handlers)
}

// Drops one wrapper; the family handlers stay so that the stack keeps handling everything.
private object StackShrinker : Shrinker<Stack> {
    override fun shrink(value: Stack): List<Stack> =
        value.handlers.indices.filter { value.handlers[it].handles == null }.map { dropped ->
            Stack(value.handlers.filterIndexed { i, _ -> i != dropped })
        }
}

/** Random complete stacks over [families], with wrappers in valid positions. */
fun arbStack(families: Set<Family> = Family.entries.toSet()): Arb<Stack> = arbitrary(StackShrinker) { rs -> rs.random.stack(families) }

/** A stack over all families but one, with a position where a handler of that family can go. */
class Slot(
    val stack: Stack,
    val at: Int,
) {
    fun fill(handler: Handler): Stack = stack.insert(at, handler)

    override fun toString(): String = "$stack with the slot at $at"
}

fun arbSlot(missing: Family): Arb<Slot> =
    arbitrary { rs ->
        val stack = rs.random.stack(Family.entries.toSet() - missing)
        Slot(stack, rs.random.nextInt(0, stack.handlers.size + 1))
    }
