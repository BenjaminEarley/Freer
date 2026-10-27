package com.benjaminearley.freer.examples

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.Generator
import com.benjaminearley.freer.effects.generator
import com.benjaminearley.freer.effects.yieldValue
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow

fun generatorExample() {
    val counter: Program<String> =
        program {
            var total = 0
            for (i in 1..4) {
                yieldValue(i).bind()
                total += i
            }
            "yielded a total of $total"
        }

    var status = counter.generator<Int, String>().runOrThrow()
    while (status is Generator.Yielded) {
        println("driver received ${status.value}")
        status = status.resume().runOrThrow()
    }
    println((status as Generator.Finished).result)
}
