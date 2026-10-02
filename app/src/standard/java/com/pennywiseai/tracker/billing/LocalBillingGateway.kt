package com.pennywiseai.tracker.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Personal builds have no purchase catalog or billing connection. */
@Singleton
class LocalBillingGateway @Inject constructor() : PurchaseGateway {

    override val isPro: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override val products: StateFlow<List<ProProduct>> =
        MutableStateFlow(emptyList<ProProduct>()).asStateFlow()

    override suspend fun refresh(): PurchaseResult = PurchaseResult.Unsupported

    override suspend fun launchPurchase(
        activity: Activity,
        product: ProProduct,
    ): PurchaseResult = PurchaseResult.Unsupported
}
