package kr.acda.arcshop.core.model

enum class TxFailureReason {
    NOT_BUYABLE,
    NOT_SELLABLE,
    INSUFFICIENT_FUNDS,
    INSUFFICIENT_ITEMS,
    LIMIT_EXCEEDED,
    NOT_OWNED,
    INTERNAL_ERROR,
}

sealed interface TxResult {
    data object Success : TxResult
    data class Failure(val reason: TxFailureReason, val message: String = "") : TxResult
}
