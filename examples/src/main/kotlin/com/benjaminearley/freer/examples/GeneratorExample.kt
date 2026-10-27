package com.benjaminearley.freer.examples

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.Status
import com.benjaminearley.freer.effects.coroutine
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

    var status = counter.coroutine().runOrThrow()
    while (status is Status.Yielded) {
        println("driver received ${status.value}")
        status = status.resume().runOrThrow()
    }
    println((status as Status.Finished).result)
}
