package com.benjaminearley.freer.examples

import com.benjaminearley.freer.Program
import com.benjaminearley.freer.trace.Path
import com.benjaminearley.freer.trace.Tree
import com.benjaminearley.freer.trace.outcome
import com.benjaminearley.freer.trace.paths
import com.benjaminearley.freer.trace.tree
import com.benjaminearley.freer.effects.Failure
import com.benjaminearley.freer.effects.raise
import com.benjaminearley.freer.examples.paymentRefund.effects.Get
import com.benjaminearley.freer.examples.paymentRefund.effects.Put
import com.benjaminearley.freer.examples.paymentRefund.effects.get
import com.benjaminearley.freer.examples.paymentRefund.effects.isFraudulent
import com.benjaminearley.freer.examples.paymentRefund.effects.logInfo
import com.benjaminearley.freer.examples.paymentRefund.effects.put
import com.benjaminearley.freer.examples.paymentRefund.transferMoney
import com.benjaminearley.freer.program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tree of the accounting flow as a test oracle. Every path of transferMoney, under both
 * fraud verdicts and balances on either side of the amount, is checked against the invariants
 * of a transfer. The copy of the flow at the bottom, without its balance check, is the kind of
 * mistake the oracle exists to catch, and the last test shows that it does.
 */
class TransferMoneyOracleTest {
    private val amount = 100.0

    // Balances around the amount: short by all of it, short by a cent, exact, and plenty.
    private val balances = listOf(0.0, 99.99, 100.0, 1000.0)

    private fun pathsOf(flow: Program<String>): List<Path> = flow.tree { ofType(Double::class, balances) }.paths()

    private fun Path.writes(): List<Put<*>> = answers.map { it.effect }.filterIsInstance<Put<*>>()

    private fun Path.raised(): Boolean = (end as? Tree.Stops)?.effect is Failure.Raise

    private fun checkInvariants(paths: List<Path>) {
        for (path in paths) {
            val description = path.answers.joinToString(" / ") { "${it.effect} = ${it.response}" } + " -> " + path.end.outcome()
            // Every path ends in TX_OK or a failure.
            assertTrue(path.end == Tree.Returns("TX_OK") || path.raised(), description)
            // A path that fails wrote nothing: a failed transfer leaves no partial write behind.
            if (path.raised()) assertTrue(path.writes().isEmpty(), description)
            // No write overdraws an account.
            for (write in path.writes()) assertTrue(write.value as Double >= 0.0, description)
            // A successful path writes exactly the two balances the transfer implies, source first.
            if (!path.raised()) {
                val read = path.answers.filter { it.effect is Get<*> }.associate { (it.effect as Get<*>).key to it.response as Double }
                assertEquals(
                    listOf("Alice" to read.getValue("Alice") - amount, "Bob" to read.getValue("Bob") + amount),
                    path.writes().map { it.key to it.value },
                    description,
                )
            }
        }
    }

    @Test
    fun everyPathOfTransferMoneyKeepsTheInvariants() {
        val paths = pathsOf(transferMoney("Alice", "Bob", amount))
        checkInvariants(paths)
        // A fraud verdict, then four source balances of which two suffice, then four target balances.
        assertEquals(1 + 2 + 2 * 4, paths.size)
    }

    @Test
    fun theOracleRejectsTheFlowWithoutTheBalanceCheck() {
        val failure = assertFailsWith<AssertionError> { checkInvariants(pathsOf(transferWithoutBalanceCheck("Alice", "Bob", amount))) }
        assertTrue("Put(key=Alice, value=-100.0)" in failure.message.orEmpty(), failure.message)
    }
}

// transferMoney with its balance check removed: it overdraws the source account.
private fun transferWithoutBalanceCheck(
    from: String,
    to: String,
    amount: Double,
): Program<String> =
    program {
        logInfo("Request: Transfer $$amount from $from to $to").bind()
        val isRisk = isFraudulent(amount, from).bind()
        if (isRisk) {
            raise("Security Alert: Fraud detected for account $from").bind()
        }
        val balance = get(from, 0.0).bind()
        put(from, balance - amount).bind()
        val targetBal = get(to, 0.0).bind()
        put(to, targetBal + amount).bind()
        logInfo("Success: Transferred $$amount. New Balance $from: $${balance - amount}").bind()
        "TX_OK"
    }
