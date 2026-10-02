package com.pennywiseai.tracker.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** All features are available without purchases or license keys in this personal build. */
@Singleton
class EntitlementGate @Inject constructor() {
    val resolvedProEntitlement: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(true).asStateFlow()

    val isProEntitled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
