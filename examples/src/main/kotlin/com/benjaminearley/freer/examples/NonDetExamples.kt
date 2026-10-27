package com.benjaminearley.freer.examples

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.choose
import com.benjaminearley.freer.effects.collectAll
import com.benjaminearley.freer.effects.guard
import com.benjaminearley.freer.effects.ifte
import com.benjaminearley.freer.effects.once
import com.benjaminearley.freer.flatMap
import com.benjaminearley.freer.map
import com.benjaminearley.freer.runOrThrow

fun nonDetExample() {
    val evens: Program<Int> =
        choose((1..10).toList()).flatMap { i ->
            guard(i % 2 == 0).map { i }
        }
    println("evens in 1..10       = ${evens.collectAll().runOrThrow()}")

    val pairs: Program<Pair<Int, Int>> =
        choose((1..3).toList()).flatMap { x ->
            choose((x..3).toList()).map { y -> x to y }
        }
    println("ordered pairs        = ${pairs.collectAll().runOrThrow()}")
}

fun committedChoiceExample() {
    val triples: Program<Triple<Int, Int, Int>> =
        choose((1..20).toList()).flatMap { x ->
            choose((x..20).toList()).flatMap { y ->
                choose((y..20).toList()).flatMap { z ->
                    guard(x * x + y * y == z * z).map { Triple(x, y, z) }
                }
            }
        }
    println("all triples in 1..20 = ${triples.collectAll().runOrThrow()}")
    println("once (rest pruned)   = ${once(triples).collectAll().runOrThrow()}")

    // Soft cut: the then-branch ranges over every factor found; the else-branch
    // runs only when a number has none.
    fun factors(n: Int): Program<Int> = choose((2 until n).toList()).flatMap { d -> guard(n % d == 0).map { d } }

    val classified =
        choose((2..8).toList()).flatMap { n ->
            ifte(factors(n), { d -> Program.Done("$n=${d}x${n / d}") }, Program.Done("$n prime"))
        }
    println("soft cut             = ${classified.collectAll().runOrThrow()}")
}
