package com.benjaminearley.freer.examples

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.ask
import com.benjaminearley.freer.effects.local
import com.benjaminearley.freer.effects.runReader
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow

fun readerExample() {
    val greet: Program<String> = program { "Hello, ${ask<String>().bind()}!" }

    println("plain           = ${greet.runReader("world").runOrThrow()}")

    val combined =
        program {
            val inner = greet.local { env: String -> env.uppercase() }.bind()
            val outer = greet.bind()
            "$inner / $outer"
        }.runReader("world").runOrThrow()
    println("local override  = $combined")
}
