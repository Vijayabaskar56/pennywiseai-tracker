package com.pennywiseai.tracker.data.sharedexpenses

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Other members' UPI IDs, so "Pay with UPI" when settling up asks for each one
 * only once. Kept in app-private SharedPreferences on this phone, keyed by
 * member ID. Never sent to the server and not part of a backup.
 */
@Singleton
class SharedUpiIds @Inject constructor(@ApplicationContext context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The UPI ID remembered for [memberId], or null. */
    suspend fun get(memberId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(memberId, null)?.takeIf { it.isNotBlank() }
    }

    /** Remembers [upiId] for [memberId]; blank or null forgets it. */
    suspend fun set(memberId: String, upiId: String?) = withContext(Dispatchers.IO) {
        val trimmed = upiId?.trim().orEmpty()
        prefs.edit { if (trimmed.isEmpty()) remove(memberId) else putString(memberId, trimmed) }
    }

    private companion object {
        const val PREFS = "shared_expenses_upi_ids"
    }
}
