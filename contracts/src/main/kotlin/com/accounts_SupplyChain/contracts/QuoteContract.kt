package com.accounts_SupplyChain.contracts

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

class QuoteContract : Contract {

    companion object {
        const val ID = "com.accounts_SupplyChain.contracts.QuoteContract"
    }


    override fun verify(tx: LedgerTransaction) {
        requireThat {
            tx.commandsOfType<Commands.Quote>()
        }
    }

    interface Commands : CommandData {
        class Quote : Commands
    }
}