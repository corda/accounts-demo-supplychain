package com.accounts_SupplyChain.contracts

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

class PolicyContract : Contract {

    companion object {
        const val ID = "com.accounts_SupplyChain.contracts.PolicyContract"
    }


    override fun verify(tx: LedgerTransaction) {
        requireThat {
            tx.commandsOfType<Commands.Create>()
        }
    }

    interface Commands : CommandData {
        class Create : Commands
    }
}