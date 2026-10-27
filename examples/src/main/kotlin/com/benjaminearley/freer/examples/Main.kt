package com.benjaminearley.freer.examples

import com.benjaminearley.freer.examples.accounting.accountingExample

fun main() {
    accountingExample()

    println("\n--- Scenario 5: Left-Associated Bind Performance ---")
    bindChainComparison()

    println("\n--- Scenario 6: Error Recovery (recover) ---")
    errorRecoveryExample()

    println("\n--- Scenario 7: Handler Ordering Decides Semantics ---")
    handlerOrderingExample()

    println("\n--- Scenario 8: Generators (reified continuations) ---")
    generatorExample()

    println("\n--- Scenario 9: Nondeterminism (multi-shot continuations) ---")
    nonDetExample()

    println("\n--- Scenario 10: Transactional State ---")
    transactionalExample()

    println("\n--- Scenario 11: Reader with local Overrides ---")
    readerExample()

    println("\n--- Scenario 12: Committed Choice (once / soft cut) ---")
    committedChoiceExample()
}
