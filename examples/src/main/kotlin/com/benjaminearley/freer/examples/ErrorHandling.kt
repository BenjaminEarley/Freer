/*
 * Error recovery and handler ordering.
 *
 * recover abandons the failed program from the failure point and substitutes
 * a fallback program; a failure inside the fallback propagates outward.
 *
 * Handler ordering decides semantics, not just plumbing: the same program run
 * with state handled outside errors keeps the state written before the failure,
 * while errors handled outside state discard it — transactional rollback for
 * free, chosen at the call site.
 */
package com.benjaminearley.freer.examples

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.effects.attempt
import com.benjaminearley.freer.effects.getState
import com.benjaminearley.freer.effects.putState
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.effects.recover
import com.benjaminearley.freer.effects.runStateWithFinal
import com.benjaminearley.freer.effects.transactional
import com.benjaminearley.freer.program
import com.benjaminearley.freer.runOrThrow

fun errorRecoveryExample() {
    val risky: Program<String> =
        program {
            raise("primary source down").bind()
        }

    // The fallback replaces the failed program from the failure point
    val recovered =
        risky
            .recover { reason -> Program.Done("fallback (was: $reason)") }
            .runOrThrow()
    println("recovered      = $recovered")

    // A failure inside the fallback escapes to the outer handler
    val rethrown: Result<String> =
        risky
            .recover { raise("fallback also down") }
            .attempt()
            .runOrThrow()
    println("rethrown       = ${rethrown.exceptionOrNull()?.message}")
}

fun handlerOrderingExample() {
    val prog: Program<Int> =
        program {
            putState(1).bind()
            putState(getState<Int>().bind() + 1).bind() // state = 2
            raise("boom").bind()
            getState<Int>().bind() // never reached
        }

    // Errors handled inside, state outside: the failure is local — state
    // written before the raise survives.
    val stateSurvives: Pair<Result<Int>, Int> =
        prog
            .attempt()
            .runStateWithFinal(0)
            .runOrThrow()
    println("attempt inside state = $stateSurvives")

    // State handled inside, errors outside: the failure is global — the
    // whole (value, state) result is rolled back.
    val stateRolledBack: Result<Pair<Int, Int>> =
        prog
            .runStateWithFinal(0)
            .attempt()
            .runOrThrow()
    println("state inside attempt = $stateRolledBack")
}

fun transactionalExample() {
    val depositThenFail: Program<String> =
        program {
            putState(getState<Int>().bind() + 100).bind()
            raise("validation failed").bind()
            "unreachable"
        }

    // Unprotected (state outside errors): the partial write survives the failure
    val leaked = depositThenFail.attempt().runStateWithFinal(500).runOrThrow()
    println("unprotected    = $leaked")

    // Transactional: writes hit a local copy; the commit never runs on failure
    val rolledBack = depositThenFail.transactional().attempt().runStateWithFinal(500).runOrThrow()
    println("transactional  = $rolledBack")

    // On success, the transaction commits its final state downstream
    val deposit = program { putState(getState<Int>().bind() + 100).bind(); "deposited" }
    val committed = deposit.transactional().attempt().runStateWithFinal(500).runOrThrow()
    println("committed      = $committed")
}
