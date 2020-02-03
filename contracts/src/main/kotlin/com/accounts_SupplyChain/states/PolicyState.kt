package com.accounts_SupplyChain.states

import com.accounts_SupplyChain.contracts.QuoteContract
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import java.util.*


@BelongsToContract(QuoteContract::class)
class PolicyState(
        val id: UUID,
        val status: String,
        val policyholderId: UUID,
        val sumInsured: Int,
        val broker: AnonymousParty,
        val insurer: AnonymousParty,
        val blocksure: AbstractParty) : ContractState {
    override val participants: List<AbstractParty> get() = listOfNotNull(insurer,blocksure).map { it }
}