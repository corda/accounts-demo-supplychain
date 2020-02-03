package com.accounts_SupplyChain.flows


import co.paralleluniverse.fibers.Suspendable
import com.accounts_SupplyChain.contracts.PolicyContract
import com.accounts_SupplyChain.states.PolicyState
import com.accounts_SupplyChain.states.QuoteState
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import net.corda.core.flows.*
import net.corda.core.identity.AnonymousParty
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.util.*
import java.util.concurrent.atomic.AtomicReference

@StartableByRPC
@StartableByService
@InitiatingFlow
class PolicyFlow(
        val insurer:String,
        val broker:String,
        val quoteId:UUID
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

        //Blocksure key
        val blocksureParty = serviceHub.myInfo.legalIdentities.single()
        val blocksureAccount = accountService.accountInfo("BlocksureAcc").single().state.data
        val blocksureKey = subFlow(NewKeyForAccount(blocksureAccount.identifier.id)).owningKey

        //Insurer key
        val insurerAccount = accountService.accountInfo(insurer).single().state.data
        val insurerParty = subFlow(RequestKeyForAccount(insurerAccount))
        val insurerKey = insurerParty.owningKey

        //Broker key
        val brokerAccount = accountService.accountInfo(broker).single().state.data
        val brokerParty = subFlow(RequestKeyForAccount(brokerAccount))
        val brokerKey = brokerParty.owningKey


        // Query to get the quote
        val criteria = QueryCriteria.VaultQueryCriteria(
                externalIds = listOf(blocksureAccount.identifier.id)
        )
        val quoteState = serviceHub.vaultService.queryBy(
                contractStateType = QuoteState::class.java,
                criteria = criteria
        ).states.filter { it.state.data.quoteId == quoteId }.firstOrNull()?.state
                ?: throw IllegalStateException("QuoteState not found.")

        //generating State for transfer
        progressTracker.currentStep = GENERATING_TRANSACTION
        val output = PolicyState(UUID.randomUUID(), "Bound", UUID.randomUUID(), quoteState.data.sumInsured, AnonymousParty(brokerKey), AnonymousParty(insurerKey), blocksureParty)
        val transactionBuilder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
        transactionBuilder.addOutputState(output)
                .addCommand(PolicyContract.Commands.Create(), listOf(blocksureKey, insurerKey))

        //Pass along Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val locallySignedTx = serviceHub.signInitialTransaction(transactionBuilder, listOfNotNull(blocksureKey))

        //Collect sigs
        progressTracker.currentStep = GATHERING_SIGS
        val sessionForInsurer = initiateFlow(insurerAccount.host)
        val insurerToMoveToSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForInsurer, insurerKey))
        val signedTransaction = locallySignedTx.withAdditionalSignatures(insurerToMoveToSignature)

        //Finalising transaction
        progressTracker.currentStep = FINALISING_TRANSACTION
        val fullySignedTx = subFlow(FinalityFlow(signedTransaction, listOf(sessionForInsurer).filter { it.counterparty != ourIdentity }))
        val movedState = fullySignedTx.coreTransaction.outRefsOfType(
                PolicyState::class.java
        ).single()
        return "Policy with ID " + movedState.state.data.id + " created."
    }
}

@InitiatedBy(PolicyFlow::class)
class PolicyFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {

        //placeholder to record account information for later use
        val accountMovedTo = AtomicReference<AccountInfo>()

        //extract account information from transaction
        val transactionSigner = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(tx: SignedTransaction) {
                accountMovedTo.set(accountService.accountInfo("Insurer").firstOrNull()?.state?.data
                        ?: throw IllegalStateException("Insurer account was not found on this node"))
            }
        }

        //record and finalize transaction
        val transaction = subFlow(transactionSigner)
        if (counterpartySession.counterparty != serviceHub.myInfo.legalIdentities.first()) {
            val recievedTx = subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = transaction.id, statesToRecord = StatesToRecord.ALL_VISIBLE))
            val accountInfo = accountMovedTo.get()

            if (accountInfo != null) {
                subFlow(BroadcastToCarbonCopyReceiversFlow(accountInfo, recievedTx.coreTransaction.outRefsOfType(PolicyState::class.java).first()))
            }
        }
    }
}

