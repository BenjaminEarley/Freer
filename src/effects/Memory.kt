package effects

import Effect
import Program
import interpretS
import perform

fun <A> Program<A>.runMemory(initialState: Int): Program<A> =
    interpretS<Memory<*>, Int, A>(initialState) { s, op, resume ->
        when (op) {
            is Recall -> resume(s, s) // return current state, state unchanged
            is Memorize -> resume(op.value, Unit) // state becomes op.value, return Unit
        }
    }

sealed interface Memory<out R> : Effect<R>

data class Memorize(
    val value: Int,
) : Memory<Unit> // Put

object Recall : Memory<Int> // Get

fun memorize(value: Int) = perform(Memorize(value))

fun recall() = perform(Recall)