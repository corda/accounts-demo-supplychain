package com.accounts_SupplyChain.contracts

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

class InternalMessageStateContract : Contract{

    companion object{
        const val ID = "com.accounts_SupplyChain.contracts.InternalMessageStateContract"
    }


    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands.Create>()
        requireThat {
            /*
             *
             * For the simplicity of the sample, we unconditionally accept all of the transactions.
             *
             */
        }
    }

    interface Commands : CommandData {
        class Create : Commands
    }
}