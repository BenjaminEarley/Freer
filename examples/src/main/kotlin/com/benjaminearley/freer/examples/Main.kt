package com.benjaminearley.freer.examples

import com.benjaminearley.freer.examples.accounting.accountingExample

fun main() {
    println("\n--- Scenario 1: Sample Program ---")
    accountingExample()

    println("\n--- Scenario 2: Left-Associated Bind Performance ---")
    bindChainComparison()

    println("\n--- Scenario 3: Error Recovery (recover) ---")
    errorRecoveryExample()

    println("\n--- Scenario 4: Handler Ordering Decides Semantics ---")
    handlerOrderingExample()

    println("\n--- Scenario 5: Generators (reified continuations) ---")
    generatorExample()

    println("\n--- Scenario 6: Nondeterminism (multi-shot continuations) ---")
    nonDetExample()

    println("\n--- Scenario 7: Transactional State ---")
    transactionalExample()

    println("\n--- Scenario 8: Reader with local Overrides ---")
    readerExample()

    println("\n--- Scenario 9: Committed Choice (once / soft cut) ---")
    committedChoiceExample()
}
