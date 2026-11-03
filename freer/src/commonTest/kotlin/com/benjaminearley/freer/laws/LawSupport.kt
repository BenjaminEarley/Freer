package com.benjaminearley.freer.laws

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.reference.Ref
import com.benjaminearley.freer.reference.run
import com.benjaminearley.freer.runOrThrow
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How many cases each law is checked on. The seed of a failing run is in its message. */
const val LAW_ITERATIONS = 500

/** Small values, so that generated arithmetic stays readable. */
val arbSmallInt: Arb<Int> = Arb.int(-3..3)

/** What [stack] makes of [program]: the final value, or the exception interpretation throws. */
fun observe(
    stack: Stack,
    program: Program<*>,
): Any? = stack.applyLib(program).runOrThrow()

fun observe(
    stack: Stack,
    program: Ref<*>,
): Any? = stack.applyRef(program).run()

/** Asserts that two programs are indistinguishable under [stack]. */
fun assertSameUnder(
    stack: Stack,
    expected: Program<*>,
    actual: Program<*>,
) {
    assertEquals(observe(stack, expected), observe(stack, actual), "under $stack")
}

/** Asserts that [outcome] is the failure of a program{} continuation resumed a second time. */
fun assertSingleShotFailure(outcome: Result<Any?>) {
    val failure = assertIs<IllegalStateException>(outcome.exceptionOrNull(), "expected the single-shot failure, got $outcome")
    assertContains(failure.message.orEmpty(), "single-shot")
}

fun <A, B> arbPair(
    first: Arb<A>,
    second: Arb<B>,
): Arb<Pair<A, B>> = Arb.bind(first, second) { a, b -> a to b }

fun <A, B, C> arbTriple(
    first: Arb<A>,
    second: Arb<B>,
    third: Arb<C>,
): Arb<Triple<A, B, C>> = Arb.bind(arbPair(first, second), third) { (a, b), c -> Triple(a, b, c) }
