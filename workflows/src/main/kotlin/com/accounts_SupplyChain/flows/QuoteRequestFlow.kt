package com.accounts_SupplyChain.flows

import co.paralleluniverse.fibers.Suspendable
import com.accounts_SupplyChain.contracts.QuoteContract
import com.accounts_SupplyChain.states.QuoteState
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import javassist.NotFoundException
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.util.*
import java.util.concurrent.atomic.AtomicReference

@StartableByRPC
@StartableByService
@InitiatingFlow
class QuoteRequestFlow(
        val broker: String,
        val insurer:String,
        val item: String,
        val sumInsured: Int
) : FlowLogic<String>() {

    companion object {
        object GENERATING_KEYS : ProgressTracker.Step("Generating Keys for transactions.")
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction for between accounts")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                QuoteRequestFlow.Companion.GENERATING_KEYS,
                QuoteRequestFlow.Companion.GENERATING_TRANSACTION,
                QuoteRequestFlow.Companion.VERIFYING_TRANSACTION,
                QuoteRequestFlow.Companion.SIGNING_TRANSACTION,
                QuoteRequestFlow.Companion.GATHERING_SIGS,
                QuoteRequestFlow.Companion.FINALISING_TRANSACTION
        )
    }

    override val progressTracker = QuoteRequestFlow.tracker()

    @Suspendable
    override fun call(): String {

        //Generate key for transaction
        progressTracker.currentStep = QuoteRequestFlow.Companion.GENERATING_KEYS
        val brokerAccount = accountService.accountInfo(broker).single().state.data
        val myKey = subFlow(NewKeyForAccount(brokerAccount.identifier.id)).owningKey

        val insurerAccount = accountService.accountInfo(insurer).single().state.data
        val targetAcctAnonymousParty = subFlow(RequestKeyForAccount(insurerAccount))
        val insurerKey = targetAcctAnonymousParty.owningKey

        //Get Blocksure node
        val blocksureParty = serviceHub.networkMapCache.getPeerByLegalName(CordaX500Name.parse("O=Blocksure,L=London,C=GB")) ?: throw NotFoundException("Blocksure party not found")
        val blocksureKey = blocksureParty.owningKey

        //generating State for transfer
        progressTracker.currentStep = QuoteRequestFlow.Companion.GENERATING_TRANSACTION
        val output = QuoteState(UUID.randomUUID(), item, sumInsured, AnonymousParty(myKey),targetAcctAnonymousParty, blocksureParty)
        val transactionBuilder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
        transactionBuilder.addOutputState(output).addCommand(QuoteContract.Commands.Quote(), listOf(insurerKey, myKey, blocksureKey))

        //Pass along Transaction
        progressTracker.currentStep = QuoteRequestFlow.Companion.SIGNING_TRANSACTION
        val locallySignedTx = serviceHub.signInitialTransaction(transactionBuilder, listOfNotNull(myKey, insurerKey, blocksureKey))

        //Collect from insurer
        progressTracker.currentStep = QuoteRequestFlow.Companion.GATHERING_SIGS
        val sessionForInsurerToSendTo = initiateFlow(insurerAccount.host)
        val accountToMoveToSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForInsurerToSendTo, insurerKey))

        //Collect from blocksure
        val sessionForBlocksureToSendTo = initiateFlow(blocksureParty)
        val blocksureSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForBlocksureToSendTo, blocksureKey))

        val signedByCounterParty = locallySignedTx.withAdditionalSignatures(sigList = accountToMoveToSignature + blocksureSignature)

        progressTracker.currentStep = QuoteRequestFlow.Companion.FINALISING_TRANSACTION
        val fullySignedTx = subFlow(FinalityFlow(signedByCounterParty, listOf(sessionForInsurerToSendTo, sessionForBlocksureToSendTo).filter { it.counterparty != ourIdentity }))
        val movedState = fullySignedTx.coreTransaction.outRefsOfType(
                QuoteState::class.java
        ).single()
        return "Quote ${movedState.state.data.quoteId} send to " + brokerAccount.host.name.organisation + "'s "+ insurerAccount.name + " team" + blocksureParty.name.toString() + " node"
    }
}

@InitiatedBy(QuoteRequestFlow::class)
class QuoteRequestFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>(){
    @Suspendable
    override fun call() {
        //placeholder to record account information for later use
        val accountMovedTo = AtomicReference<AccountInfo>()

        //extract account information from transaction
        val transactionSigner = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(tx: SignedTransaction) {
                val keyStateMovedTo = tx.coreTransaction.outRefsOfType(QuoteState::class.java).first().state.data.insurer
                keyStateMovedTo?.let {
                    accountMovedTo.set(accountService.accountInfo(keyStateMovedTo.owningKey)?.state?.data)
                }
                if (accountMovedTo.get() == null) {
                    throw IllegalStateException("Account to move to was not found on this node")
                }
            }
        }
        //record and finalize transaction
        val transaction = subFlow(transactionSigner)
        if (counterpartySession.counterparty != serviceHub.myInfo.legalIdentities.first()) {
            val recievedTx = subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = transaction.id, statesToRecord = StatesToRecord.ALL_VISIBLE))
            val accountInfo = accountMovedTo.get()
            if (accountInfo != null) {
                subFlow(BroadcastToCarbonCopyReceiversFlow(accountInfo, recievedTx.coreTransaction.outRefsOfType(QuoteState::class.java).first()))
            }
        }
    }

}
