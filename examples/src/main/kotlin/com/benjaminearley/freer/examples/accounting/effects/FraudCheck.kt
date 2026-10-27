package com.benjaminearley.freer.examples.accounting.effects

import com.benjaminearley.freer.Effect
import com.benjaminearley.freer.Program
import com.benjaminearley.freer.handle
import com.benjaminearley.freer.intercept
import com.benjaminearley.freer.perform

// Pure handler: just decides if the transaction is fraudulent
fun <A> Program<A>.fraudCheck(): Program<A> =
    handle<FraudCheck<*>, A> { op ->
        when (op) {
            is VerifyTransaction -> op.amount > 5000.0
        }
    }

// Middleware: logs suspicious transactions without owning the fraud logic
fun <A> Program<A>.auditFraudCheck(): Program<A> =
    intercept<FraudCheck<*>, A> { op, proceed ->
        when (op) {
            is VerifyTransaction -> {
                val isSus = proceed()
                if (isSus == true) {
                    logWarn("Flagging transaction for review...").bind()
                }
                isSus
            }
        }
    }

sealed interface FraudCheck<out R> : Effect<R>

data class VerifyTransaction(
    val amount: Double,
    val accountId: String,
) : FraudCheck<Boolean>

fun isFraudulent(
    amount: Double,
    accountId: String,
) = perform(VerifyTransaction(amount, accountId))
