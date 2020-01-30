package com.accounts_SupplyChain.flows


import net.corda.core.flows.*
import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount


import com.accounts_SupplyChain.contracts.InvoiceStateContract
import com.accounts_SupplyChain.contracts.PolicyContract
import com.accounts_SupplyChain.states.InternalMessageState
import com.accounts_SupplyChain.states.InvoiceState
import com.accounts_SupplyChain.states.PolicyState
import com.accounts_SupplyChain.states.QuoteState
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AnonymousParty
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.ProgressTracker

@StartableByRPC
@StartableByService
@InitiatingFlow
class PolicyFlow(
        val insurer: String,
        val broker:String,
        val quoteId:UUID
) : FlowLogic<String>(){

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
                GENERATING_KEYS,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()



    @Suspendable
    override fun call(): String {

        //Generate key for transaction
        progressTracker.currentStep = GENERATING_KEYS

        val blocksureParty = serviceHub.myInfo.legalIdentities.single()

        val insurerAccount = accountService.accountInfo(insurer).single().state.data
        val insurerKey = subFlow(NewKeyForAccount(insurerAccount.identifier.id)).owningKey

        val brokerAccount = accountService.accountInfo(broker).single().state.data
        val brokerKey = subFlow(NewKeyForAccount(brokerAccount.identifier.id)).owningKey

        // Query to get the quote
        val criteria = QueryCriteria.VaultQueryCriteria(
                externalIds = listOf(insurerAccount.identifier.id)
        )
        val quoteState = serviceHub.vaultService.queryBy(
                contractStateType = QuoteState::class.java,
                criteria = criteria
        ).states.filter { it.state.data.quoteId == quoteId }.first().state



        //generating State for transfer
        progressTracker.currentStep = GENERATING_TRANSACTION
        val output = PolicyState(UUID.randomUUID(), "Bound", UUID.randomUUID(), quoteState.data.sumInsured, AnonymousParty(brokerKey), AnonymousParty(insurerKey), blocksureParty)
        val transactionBuilder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
        transactionBuilder.addOutputState(output)
                .addCommand(PolicyContract.Commands.Create(), listOf(blocksureParty.owningKey, insurerKey, brokerKey))

        //Pass along Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val locallySignedTx = serviceHub.signInitialTransaction(transactionBuilder, listOfNotNull(ourIdentity.owningKey, insurerKey, brokerKey))


        //Collect broker sigs
        progressTracker.currentStep =GATHERING_SIGS
        val sessionForBroker = initiateFlow(brokerAccount.host)
        val brokerToMoveToSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForBroker, brokerKey))

        //Collect insurer sigs
        val sessionForInsurer = initiateFlow(insurerAccount.host)
        val insurerToMoveToSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForInsurer, insurerKey))
        val signedTransaction = locallySignedTx.withAdditionalSignatures(brokerToMoveToSignature + insurerToMoveToSignature)

        //Finalising transaction
        progressTracker.currentStep =FINALISING_TRANSACTION
        val fullySignedTx = subFlow(FinalityFlow(signedTransaction, listOf(sessionForBroker, sessionForInsurer).filter { it.counterparty != ourIdentity }))
        val movedState = fullySignedTx.coreTransaction.outRefsOfType(
                PolicyState::class.java

        ).single()
        return "Policy with ID " + output.id + " send to " + brokerAccount.name + " and " + insurerAccount.name
    }
}

@InitiatedBy(PolicyFlow::class)
class PolicyFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>(){
    @Suspendable
    override fun call() {
        //placeholder to record account information for later use
        val insurerAccount = AtomicReference<AccountInfo>()
        val brokerAccount = AtomicReference<AccountInfo>()

        //extract account information from transaction
        val transactionSigner = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(tx: SignedTransaction) {
                tx.coreTransaction.outRefsOfType(PolicyState::class.java).first().state.data.insurer.run { insurerAccount.set(accountService.accountInfo(this.owningKey)?.state?.data) }
                tx.coreTransaction.outRefsOfType(PolicyState::class.java).first().state.data.broker.run { brokerAccount.set(accountService.accountInfo(this.owningKey)?.state?.data) }
                if (insurerAccount.get() == null || brokerAccount.get() == null) {
                    throw IllegalStateException("Some of the accounts were not found on this node")
                }
            }
        }
        //record and finalize transaction
        val transaction = subFlow(transactionSigner)
        if (counterpartySession.counterparty != serviceHub.myInfo.legalIdentities.first()) {
            val recievedTx = subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = transaction.id, statesToRecord = StatesToRecord.ALL_VISIBLE))
            val insurerAccountInfo = insurerAccount.get()
            val brokerAccountInfo = brokerAccount.get()

            if (insurerAccountInfo != null) {
                subFlow(BroadcastToCarbonCopyReceiversFlow(insurerAccountInfo, recievedTx.coreTransaction.outRefsOfType(PolicyState::class.java).first()))
            }

            if (brokerAccountInfo != null) {
                subFlow(BroadcastToCarbonCopyReceiversFlow(brokerAccountInfo, recievedTx.coreTransaction.outRefsOfType(PolicyState::class.java).first()))
            }
        }
    }

}
/*
*
* UUID is prefered to be used when identifying account, because there
* might be multiple accounts from different nodes that has the same name.
*
* */

//class SendInvoice(
//        val whoAmI: UUID,
//        val whereToUUID:UUID,
//        val amount:Int
//) : FlowLogic<String>(){
//
//    companion object {
//        object GENERATING_KEYS : ProgressTracker.Step("Generating Keys for transactions.")
//        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction for between accounts")
//        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
//        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
//        object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
//            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
//        }
//
//        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
//            override fun childProgressTracker() = FinalityFlow.tracker()
//        }
//
//        fun tracker() = ProgressTracker(
//                GENERATING_KEYS,
//                GENERATING_TRANSACTION,
//                VERIFYING_TRANSACTION,
//                SIGNING_TRANSACTION,
//                GATHERING_SIGS,
//                FINALISING_TRANSACTION
//        )
//    }
//
//    override val progressTracker = tracker()
//
//
//
//    @Suspendable
//    override fun call(): String {
//
//        //Create a key for Loan transaction
//        progressTracker.currentStep = GENERATING_KEYS
//        val myAccount = accountService.accountInfo(whoAmI)?.state!!.data
//        val myKey = subFlow(NewKeyForAccount(myAccount.identifier.id)).owningKey
//        val targetAccount = accountService.accountInfo(whereToUUID)?.state!!.data
//        val targetAcctAnonymousParty = subFlow(RequestKeyForAccount(targetAccount))
//
//
//
//        //generating State for transfer
//        progressTracker.currentStep = GENERATING_TRANSACTION
//        val output = InvoiceState(amount, AnonymousParty(myKey),targetAcctAnonymousParty,UUID.randomUUID())
//        val transactionBuilder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
//        transactionBuilder.addOutputState(output)
//                .addCommand(InvoiceStateContract.Commands.Create(), listOf(targetAcctAnonymousParty.owningKey,myKey))
//
//        //Pass along Transaction
//        progressTracker.currentStep = SIGNING_TRANSACTION
//        val locallySignedTx = serviceHub.signInitialTransaction(transactionBuilder, listOfNotNull(ourIdentity.owningKey,myKey))
//
//
//        //Collect sigs
//        progressTracker.currentStep =GATHERING_SIGS
//        val sessionForAccountToSendTo = initiateFlow(targetAccount.host)
//        val accountToMoveToSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForAccountToSendTo, targetAcctAnonymousParty.owningKey))
//        val signedByCounterParty = locallySignedTx.withAdditionalSignatures(accountToMoveToSignature)
//
//        progressTracker.currentStep =FINALISING_TRANSACTION
//        val fullySignedTx = subFlow(FinalityFlow(signedByCounterParty, listOf(sessionForAccountToSendTo).filter { it.counterparty != ourIdentity }))
//        val movedState = fullySignedTx.coreTransaction.outRefsOfType(
//                InvoiceState::class.java
//
//        ).single()
//        return "Invoice send to " + targetAccount.host + "'s "+ targetAccount.name
//    }
//}
//
//@InitiatedBy(SendInvoice::class)
//class SendInvoiceResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>(){
//    @Suspendable
//    override fun call() {
//        val accountMovedTo = AtomicReference<AccountInfo>()
//        val transactionSigner = object : SignTransactionFlow(counterpartySession) {
//            override fun checkTransaction(tx: SignedTransaction) {
//                val keyStateMovedTo = tx.coreTransaction.outRefsOfType(InvoiceState::class.java).first().state.data.recipient
//                keyStateMovedTo?.let {
//                    accountMovedTo.set(accountService.accountInfo(keyStateMovedTo.owningKey)?.state?.data)
//                }
//                if (accountMovedTo.get() == null) {
//                    throw IllegalStateException("Account to move to was not found on this node")
//                }
//
//            }
//        }
//        val transaction = subFlow(transactionSigner)
//        if (counterpartySession.counterparty != serviceHub.myInfo.legalIdentities.first()) {
//            val recievedTx = subFlow(
//                    ReceiveFinalityFlow(
//                            counterpartySession,
//                            expectedTxId = transaction.id,
//                            statesToRecord = StatesToRecord.ALL_VISIBLE
//                    )
//            )
//            val accountInfo = accountMovedTo.get()
//            if (accountInfo != null) {
//                subFlow(BroadcastToCarbonCopyReceiversFlow(accountInfo, recievedTx.coreTransaction.outRefsOfType(InvoiceState::class.java).first()))
//            }
//
//
//
//        }
//    }
//
//}




