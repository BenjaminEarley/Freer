/*
 * The specification interpreter.
 *
 * A program is a plain tree: either a value, or a request paired with a closure that builds
 * the rest of the program from the response. Binding nests a closure, a handler walks the
 * tree recursively, and nothing is trampolined, queued or cached. That makes it slow and
 * unsafe on deep programs, and easy to read: every operation is a few lines whose meaning
 * is visible at a glance. It exists only in tests, to say what the optimized interpreter
 * must compute.
 */
@file:Suppress("UNCHECKED_CAST")

package com.benjaminearley.freer.reference

import com.benjaminearley.freer.Effect
import kotlin.reflect.KClass

sealed class Ref<out A> {
    /** A finished program holding its value. */
    class Pure<out A>(
        val value: A,
    ) : Ref<A>()

    /** A program paused on [effect]; [next] builds the rest from the response. */
    class Impure<out A>(
        val effect: Effect<*>,
        val next: (Any?) -> Ref<A>,
    ) : Ref<A>()
}

fun <A> pure(value: A): Ref<A> = Ref.Pure(value)

/** Request [effect] and finish with its response. */
fun <R> send(effect: Effect<R>): Ref<R> = Ref.Impure(effect) { response -> Ref.Pure(response as R) }

fun <A, B> Ref<A>.flatMap(f: (A) -> Ref<B>): Ref<B> =
    when (this) {
        is Ref.Pure -> f(value)
        is Ref.Impure -> Ref.Impure(effect) { response -> next(response).flatMap(f) }
    }

fun <A, B> Ref<A>.map(f: (A) -> B): Ref<B> = flatMap { pure(f(it)) }

/** The value of a program whose effects have all been handled. */
fun <A> Ref<A>.run(): A =
    when (this) {
        is Ref.Pure -> value
        is Ref.Impure -> error("Unhandled effect: $effect")
    }

/**
 * Handle every effect of family [type] with [rule], and the final value with [ret]. The
 * continuation handed to the rule feeds the response to the program and keeps handling the
 * rest. An effect of any other family is passed on unchanged with that same continuation,
 * so the handler is back in place when the response arrives.
 */
fun <E : Effect<*>, A, B> Ref<A>.handleRelay(
    type: KClass<E>,
    ret: (A) -> Ref<B>,
    rule: (E, (Any?) -> Ref<B>) -> Ref<B>,
): Ref<B> {
    fun loop(program: Ref<A>): Ref<B> =
        when (program) {
            is Ref.Pure -> ret(program.value)
            is Ref.Impure -> {
                val continuation: (Any?) -> Ref<B> = { response -> loop(program.next(response)) }
                if (type.isInstance(program.effect)) {
                    rule(program.effect as E, continuation)
                } else {
                    Ref.Impure(program.effect, continuation)
                }
            }
        }
    return loop(this)
}

inline fun <reified E : Effect<*>, A, B> Ref<A>.handleRelay(
    noinline ret: (A) -> Ref<B>,
    noinline rule: (E, (Any?) -> Ref<B>) -> Ref<B>,
): Ref<B> = handleRelay(E::class, ret, rule)

/**
 * [handleRelay] with a state threaded through: the rule receives the state as it is and
 * resumes with the state as it will be. An effect passed on carries the state along, so
 * the handler resumes with the state as of the moment it passed the effect on.
 */
fun <E : Effect<*>, S, A, B> Ref<A>.handleRelayS(
    type: KClass<E>,
    initial: S,
    ret: (S, A) -> Ref<B>,
    rule: (S, E, (S, Any?) -> Ref<B>) -> Ref<B>,
): Ref<B> {
    fun loop(
        state: S,
        program: Ref<A>,
    ): Ref<B> =
        when (program) {
            is Ref.Pure -> ret(state, program.value)
            is Ref.Impure -> {
                val continuation: (S, Any?) -> Ref<B> = { next, response -> loop(next, program.next(response)) }
                if (type.isInstance(program.effect)) {
                    rule(state, program.effect as E, continuation)
                } else {
                    Ref.Impure(program.effect) { response -> continuation(state, response) }
                }
            }
        }
    return loop(initial, this)
}

inline fun <reified E : Effect<*>, S, A, B> Ref<A>.handleRelayS(
    initial: S,
    noinline ret: (S, A) -> Ref<B>,
    noinline rule: (S, E, (S, Any?) -> Ref<B>) -> Ref<B>,
): Ref<B> = handleRelayS(E::class, initial, ret, rule)

/**
 * Handle effects of family [type] inside a program that may keep using the family: the rule
 * answers a request itself or re-requests it, and the family stays available afterwards.
 * With effects told apart by class at run time this is [handleRelay] under another name;
 * the name records which of the two a handler is.
 */
inline fun <reified E : Effect<*>, A, B> Ref<A>.interpose(
    noinline ret: (A) -> Ref<B>,
    noinline rule: (E, (Any?) -> Ref<B>) -> Ref<B>,
): Ref<B> = handleRelay(E::class, ret, rule)
