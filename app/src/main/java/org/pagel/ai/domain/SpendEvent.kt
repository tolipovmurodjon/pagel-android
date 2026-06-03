package org.pagel.ai.domain

import kotlinx.serialization.Serializable

/** A parsed money amount from a bank notification (candidate spend — Boss confirms before it logs). */
@Serializable
data class SpendEvent(
    val ts: Long,
    val amount: Long,   // in so'm
    val raw: String,    // trimmed source text (for context / debugging the parser)
)
