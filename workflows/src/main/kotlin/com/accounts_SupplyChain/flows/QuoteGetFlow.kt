package com.accounts_SupplyChain.flows


import co.paralleluniverse.fibers.Suspendable
import com.accounts_SupplyChain.states.QuoteState
import com.r3.corda.lib.accounts.workflows.accountService
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.core.node.services.vault.QueryCriteria


@StartableByRPC
@StartableByService
@InitiatingFlow
class QuoteGetFlow(
        val acctname : String
) : FlowLogic<List<String>>() {

    @Suspendable
    override fun call(): List<String> {

        val myAccount = accountService.accountInfo(acctname).single().state.data
        val criteria = QueryCriteria.VaultQueryCriteria(
                externalIds = listOf(myAccount.identifier.id)
        )

        val quotes = serviceHub.vaultService.queryBy(
                contractStateType = QuoteState::class.java,
                criteria = criteria
        ).states.map { "\n" + "Quotes:\n " +
                "quoteId:" + it.state.data.quoteId + "\n" +
                "itemName:" + it.state.data.itemName + "\n" +
                "sumInsured:" + it.state.data.sumInsured + "\n" +
                "broker:" + it.state.data.broker + "\n" +
                "insurer:" + it.state.data.insurer + "\n" +
                "blocksure:" + it.state.data.blocksure + "\n"
        }

        return quotes
    }
}



