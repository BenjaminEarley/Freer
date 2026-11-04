package com.benjaminearley.freer.jcstress

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.Generator
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.generator
import com.benjaminearley.freer.effects.yieldValue
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.handleS
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.map
import com.benjaminearley.freer.perform
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow

/** Answered with the current count, which then advances by one. */
sealed interface Counter<out R> : Effect<R>

data object Next : Counter<Int>

/** Answered with the environment of whichever handler the interpreting thread applied. */
sealed interface Env<out R> : Effect<R>

data object AskEnv : Env<Int>

fun next(): Program<Int> = perform(Next)

fun askEnv(): Program<Int> = perform(AskEnv)

fun <A> Program<A>.count(from: Int): Program<A> = handleS<Counter<*>, Int, A>(from) { n, _ -> (n + 1) to n }

fun <A> Program<A>.env(value: Int): Program<A> = handle<Env<*>, A> { value }

/**
 * The Program values the JCStress tests share between their actors. Each is built once,
 * when this class loads, and interpreted by every actor of every iteration. The values the
 * tests expect are the ones a single thread computes.
 */
object SharedPrograms {
    /** Eight counter reads and one environment read in one block: 28 plus the environment. */
    @JvmField
    val block: Program<Int> =
        program {
            var total = 0
            repeat(8) { total += next().bind() }
            total + askEnv().bind()
        }

    /** [block] with its handlers already applied: 128. */
    @JvmField
    val handled: Program<Int> = block.count(0).env(100)

    /** [block] behind a middleware that doubles the environment on its way through: 228. */
    @JvmField
    val intercepted: Program<Int> =
        block.intercept<Env<*>, Int> { _, proceed -> (proceed() as Int) * 2 }.count(0).env(100)

    /**
     * Three branches, each a block of its own that reads the counter and the environment,
     * collected under one counter that the branches share: 1·10 + 2·11 + 3·12 plus three
     * environments, 368.
     */
    @JvmField
    val search: Program<Int> =
        choose(listOf(1, 2, 3))
            .flatMap { x -> program { x * next().bind() + askEnv().bind() } }
            .collectAll()
            .count(10)
            .env(100)
            .map { it.sum() }

    /** A generator of 1, 2 and 3 built from flatMap alone, so that its continuations are multi-shot. */
    @JvmField
    val generator: Program<Generator<Int, Int>> =
        yieldValue(1).flatMap { yieldValue(2) }.flatMap { yieldValue(3) }.map { 7 }.generator()

    /** The generator paused at its first yield, whose continuation every actor resumes. */
    @JvmField
    val firstYield: Generator.Yielded<Int, Int> =
        when (val step = generator.runOrThrow()) {
            is Generator.Yielded -> step
            is Generator.Finished -> error("the generator finished without yielding")
        }

    @JvmStatic
    fun runHandled(): Int = handled.runOrThrow()

    @JvmStatic
    fun runIntercepted(): Int = intercepted.runOrThrow()

    @JvmStatic
    fun runSearch(): Int = search.runOrThrow()

    /** [block] under handlers of the calling thread's own, with [environment] as the environment. */
    @JvmStatic
    fun runBlock(environment: Int): Int = block.count(0).env(environment).runOrThrow()

    /** The value the generator yields after [firstYield], 2, or the negated final value if it finishes. */
    @JvmStatic
    fun resumeFirstYield(): Int =
        when (val step = firstYield.resume().runOrThrow()) {
            is Generator.Yielded -> step.value
            is Generator.Finished -> -step.result
        }

    /**
     * A new handled program, built on the calling thread, whose block captures [environment]
     * and whose value is 28 more than it.
     */
    @JvmStatic
    fun build(environment: Int): Program<Int> =
        program {
            var total = environment
            repeat(8) { total += next().bind() }
            total
        }.count(0)

    @JvmStatic
    fun run(program: Program<Int>): Int = program.runOrThrow()
}
