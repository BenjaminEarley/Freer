/*
 * The built-in effects, handled by the specification interpreter. Each handler is the direct
 * recursive definition, under the same name as the optimized handler it specifies.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.reference

import com.benjaminearley.freer.Either
import com.benjaminearley.freer.Left
import com.benjaminearley.freer.Right
import com.benjaminearley.freer.effects.Emit
import com.benjaminearley.freer.effects.Failure
import com.benjaminearley.freer.effects.NonDet
import com.benjaminearley.freer.effects.Reader
import com.benjaminearley.freer.effects.State

// Reader

fun <E> ask(): Ref<E> = send(Reader.Ask) as Ref<E>

fun <E, A> Ref<A>.runReader(env: E): Ref<A> = handleRelay<Reader<*>, A, A>({ pure(it) }) { _, resume -> resume(env) }

/** Answer the asks inside this program with the environment transformed once, on entry. */
fun <E, A> Ref<A>.local(transform: (E) -> E): Ref<A> =
    ask<E>().flatMap { outer ->
        val env = transform(outer)
        this@local.interpose<Reader<*>, A, A>({ pure(it) }) { _, resume -> resume(env) }
    }

// State

fun <S> getState(): Ref<S> = send(State.Get) as Ref<S>

fun <S> putState(value: S): Ref<Unit> = send(State.Put(value))

fun <S> modifyState(f: (S) -> S): Ref<Unit> = getState<S>().flatMap { putState(f(it)) }

fun <S, A> Ref<A>.runStateWithFinal(initial: S): Ref<Pair<A, S>> =
    handleRelayS<State<*>, S, A, Pair<A, S>>(initial, { state, value -> pure(value to state) }) { state, op, resume ->
        when (op) {
            is State.Get -> resume(state, state)
            is State.Put -> resume(op.value as S, Unit)
        }
    }

fun <S, A> Ref<A>.runState(initial: S): Ref<A> = runStateWithFinal(initial).map { it.first }

/**
 * Run this program against a private copy of the state, read from the enclosing handler on
 * entry and written back only when the program finishes. A loop threads the copy, answering
 * reads and writes itself and passing every other effect on, so a program that an outer
 * handler abandons part-way never writes back.
 */
fun <A> Ref<A>.transactional(): Ref<A> {
    fun loop(
        state: Any?,
        program: Ref<A>,
    ): Ref<A> =
        when (program) {
            is Ref.Pure -> putState(state).map { program.value }
            is Ref.Impure ->
                when (val effect = program.effect) {
                    is State.Get -> loop(state, program.next(state))
                    is State.Put -> loop(effect.value, program.next(Unit))
                    else -> Ref.Impure(effect) { response -> loop(state, program.next(response)) }
                }
        }
    return getState<Any?>().flatMap { snapshot -> loop(snapshot, this) }
}

// Failure

fun raise(reason: String): Ref<Nothing> = send(Failure.Raise(reason))

fun <A> Ref<A>.attempt(): Ref<Either<String, A>> =
    handleRelay<Failure<*>, A, Either<String, A>>({ pure(Right(it)) }) { op, _ ->
        when (op) {
            is Failure.Raise -> pure(Left(op.reason))
        }
    }

fun <A> Ref<A>.recover(fallback: (String) -> Ref<A>): Ref<A> =
    interpose<Failure<*>, A, A>({ pure(it) }) { op, _ ->
        when (op) {
            is Failure.Raise -> fallback(op.reason)
        }
    }

// Nondeterminism

fun <T> choose(options: List<T>): Ref<T> = send(NonDet.Choose(options)) as Ref<T>

fun guard(condition: Boolean): Ref<Unit> = if (condition) pure(Unit) else choose(emptyList())

fun <A> orElse(
    first: Ref<A>,
    second: Ref<A>,
): Ref<A> = choose(listOf(true, false)).flatMap { if (it) first else second }

/** Every answer, in order: a choice runs the rest once per option and concatenates. */
fun <A> Ref<A>.collectAll(): Ref<List<A>> =
    handleRelay<NonDet<*>, A, List<A>>({ pure(listOf(it)) }) { op, resume ->
        when (op) {
            is NonDet.Choose ->
                op.options.fold(pure(emptyList<A>())) { collected, option ->
                    collected.flatMap { answers -> resume(option).map { answers + it } }
                }
        }
    }

/**
 * The first answer paired with the rest of the search, or null when there is none. The
 * search is depth first with an explicit list of the branches not yet tried: a choice
 * pushes its options onto the list, a dead end pops the next one, and the first value
 * found is returned with the untried branches re-joined into one program.
 */
fun <A> Ref<A>.msplit(): Ref<Pair<A, Ref<A>>?> {
    fun loop(
        untried: List<() -> Ref<A>>,
        program: Ref<A>,
    ): Ref<Pair<A, Ref<A>>?> =
        when (program) {
            is Ref.Pure -> pure(program.value to anyOf(untried))
            is Ref.Impure -> {
                val effect = program.effect
                if (effect is NonDet.Choose) {
                    val branches = effect.options.map { option -> { program.next(option) } } + untried
                    if (branches.isEmpty()) pure(null) else loop(branches.drop(1), branches.first()())
                } else {
                    Ref.Impure(effect) { response -> loop(untried, program.next(response)) }
                }
            }
        }
    return loop(emptyList(), this)
}

// One program with the answers of every branch in order; a branch is built only if the search reaches it.
private fun <A> anyOf(branches: List<() -> Ref<A>>): Ref<A> =
    branches.foldRight(choose(emptyList())) { branch, rest ->
        choose(listOf(true, false)).flatMap { if (it) branch() else rest }
    }

fun <A> unsplit(split: Pair<A, Ref<A>>?): Ref<A> = if (split == null) choose(emptyList()) else orElse(pure(split.first), split.second)

fun <A> once(search: Ref<A>): Ref<A> = search.msplit().flatMap { split -> if (split == null) choose(emptyList()) else pure(split.first) }

fun <A, B> ifte(
    condition: Ref<A>,
    then: (A) -> Ref<B>,
    otherwise: Ref<B>,
): Ref<B> =
    condition.msplit().flatMap { split ->
        if (split == null) otherwise else orElse(then(split.first), split.second.flatMap(then))
    }

// Generators

/** One step of a generator: finished with a result, or paused on a value with the rest as a callable. */
sealed class Coroutine<out T, out A> {
    class Finished<out A>(
        val result: A,
    ) : Coroutine<Nothing, A>()

    class Yielded<out T, out A>(
        val value: T,
        val resume: () -> Ref<Coroutine<T, A>>,
    ) : Coroutine<T, A>()
}

fun <T> yieldValue(value: T): Ref<Unit> = send(Emit.Yield(value))

fun <T, A> Ref<A>.generator(): Ref<Coroutine<T, A>> =
    handleRelay<Emit<*>, A, Coroutine<T, A>>({ pure(Coroutine.Finished(it)) }) { op, resume ->
        when (op) {
            is Emit.Yield<*> -> pure(Coroutine.Yielded(op.value as T) { resume(Unit) })
        }
    }
