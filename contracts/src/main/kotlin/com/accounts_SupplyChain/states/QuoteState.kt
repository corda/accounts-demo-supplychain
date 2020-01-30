package com.accounts_SupplyChain.states

import com.accounts_SupplyChain.contracts.QuoteContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty

@BelongsToContract(QuoteContract::class)
class QuoteState(
        val itemName: String,
        val sumInsured: Int,
        val broker: AnonymousParty,
        val insurer: AnonymousParty,
        val blockaure: AbstractParty) : ContractState {
    override val participants: List<AbstractParty> get() = listOfNotNull(broker,insurer,blockaure).map { it }
}