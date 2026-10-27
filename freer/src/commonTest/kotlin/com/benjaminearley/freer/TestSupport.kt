package com.benjaminearley.freer

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

sealed interface Ask<out R> : Effect<R>

data object AskInt : Ask<Int>

sealed interface Out<out R> : Effect<R>

data class Say(
    val s: String,
) : Out<Unit>

// Runs a suspend block that completes without suspending and returns its result.
fun <T> runSync(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended" }.getOrThrow()
}

// Asserts that block fails because a rule returned a Program where it should have bound one.
fun assertMissingBind(block: () -> Any?) {
    val e = assertFailsWith<IllegalStateException> { block() }
    assertContains(e.message.orEmpty(), ".bind()")
}

// Asserts that block fails because an interpret rule broke the resume contract.
fun assertContractViolation(block: () -> Any?) {
    val e = assertFailsWith<IllegalStateException> { block() }
    assertContains(e.message.orEmpty(), "rule contract")
}
