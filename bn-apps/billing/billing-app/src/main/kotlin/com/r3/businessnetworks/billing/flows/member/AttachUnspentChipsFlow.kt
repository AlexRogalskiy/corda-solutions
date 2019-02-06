package com.r3.businessnetworks.billing.flows.member

import co.paralleluniverse.fibers.Suspendable
import com.r3.businessnetworks.billing.flows.Constants.TIME_TOLERANCE
import com.r3.businessnetworks.billing.flows.member.service.MemberDatabaseService
import com.r3.businessnetworks.billing.states.BillingChipState
import com.r3.businessnetworks.billing.states.BillingContract
import com.r3.businessnetworks.billing.states.BillingState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.time.Duration
import java.time.Instant

/**
 * Attaches [unspentChips] to the [billingState]
 */
@StartableByRPC
@InitiatingFlow
class AttachUnspentChipsFlow(private val billingState : StateAndRef<BillingState>,
                             private val unspentChips : List<StateAndRef<BillingChipState>>,
                             private val timeTolerance : Duration = TIME_TOLERANCE) : FlowLogic<Pair<BillingState, SignedTransaction>>() {
    @Suspendable
    override fun call() : Pair<BillingState, SignedTransaction> {
        val notary = serviceHub.networkMapCache.notaryIdentities.single()
        val builder = TransactionBuilder(notary)
                .addInputState(billingState)
                .addCommand(BillingContract.Commands.AttachBack(), ourIdentity.owningKey)

        if (billingState.state.data.expiryDate != null) {
            builder.setTimeWindow(Instant.now(), timeTolerance)
        }

        var totalUnspent = 0L
        unspentChips.forEach {
            builder.addInputState(it)
            totalUnspent += it.state.data.amount
        }
        val outputBillingState = billingState.state.data.copy(spent = billingState.state.data.spent - totalUnspent)
        builder.addOutputState(outputBillingState, BillingContract.CONTRACT_NAME)

        builder.verify(serviceHub)

        val stx = serviceHub.signInitialTransaction(builder)

        val sessionWithIssuer = initiateFlow(billingState.state.data.issuer)

        return Pair(outputBillingState, subFlow(FinalityFlow(stx, listOf(sessionWithIssuer))))
    }
}

/**
 * Fetches all unspent chips for [billingState] and attaches them to the state.
 * Returns null if there are no unspent chips.
 */
@StartableByRPC
class AttachAllUnspentChipsFlow(private val billingState : StateAndRef<BillingState>): FlowLogic<Pair<BillingState, SignedTransaction>?>()  {
    @Suspendable
    override fun call() : Pair<BillingState, SignedTransaction>? {
        val databaseService = serviceHub.cordaService(MemberDatabaseService::class.java)
        // fetching all unspent chips from vault
        val unspentChips = databaseService.getChipsByBillingState(billingState.state.data.linearId)
        return if (!unspentChips.isEmpty()) subFlow(AttachUnspentChipsFlow(billingState, unspentChips))
        else null
    }
}