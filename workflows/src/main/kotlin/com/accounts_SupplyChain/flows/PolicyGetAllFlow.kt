package com.accounts_SupplyChain.flows


import net.corda.core.flows.*
import co.paralleluniverse.fibers.Suspendable
import com.accounts_SupplyChain.states.*
import com.r3.corda.lib.accounts.workflows.accountService
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.vault.QueryCriteria


@StartableByRPC
@StartableByService
@InitiatingFlow
class PolicyGetAllFlow() : FlowLogic<List<String>>() {

    @Suspendable
    override fun call(): List<String> {

        val quotes = serviceHub.vaultService.queryBy(
                contractStateType = PolicyState::class.java
        ).states.map { "\n" + "Policies:\n " +
                "id:" + it.state.data.id + "\n" +
                "status:" + it.state.data.status + "\n" +
                "policyholderId:" + it.state.data.policyholderId + "\n" +
                "sumInsured:" + it.state.data.sumInsured + "\n" +
                "broker:" + it.state.data.broker + "\n" +
                "insurer:" + it.state.data.insurer + "\n" +
                "blocksure:" + it.state.data.blocksure + "\n"
        }

        return quotes
    }
}



