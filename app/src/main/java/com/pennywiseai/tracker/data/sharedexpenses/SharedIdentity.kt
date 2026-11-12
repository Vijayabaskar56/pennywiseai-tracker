package com.pennywiseai.tracker.data.sharedexpenses

import android.content.Context
import androidx.core.content.edit
import com.pennywiseai.tracker.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's Shared expenses identity: the server address, the user ID and
 * secret from `POST /v1/device` (the secret sealed with an Android Keystore key),
 * and the display name. Kept in app-private SharedPreferences.
 */
@Singleton
class SharedIdentity @Inject constructor(
    @ApplicationContext context: Context,
    private val remote: SharedRemote,
) : SharedSession {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val registerLock = Mutex()

    @Volatile private var registration: SharedCredentials? = loadRegistration()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SharedIdentityState> = _state.asStateFlow()

    /** The server in use: the one entered in settings, else the build default; "" when neither is set. */
    val serverAddress: String get() = prefs.getString(KEY_SERVER, null) ?: DEFAULT_SERVER

    val displayName: String? get() = prefs.getString(KEY_NAME, null)

    /** Credentials for [serverAddress]; a registration made with another server is never used. */
    override val credentials: SharedCredentials? get() = registration?.takeIf { it.baseUrl == serverAddress }

    override var lastSyncAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 }
        set(value) = prefs.edit { if (value == null) remove(KEY_LAST_SYNC) else putLong(KEY_LAST_SYNC, value) }

    private fun loadRegistration(): SharedCredentials? {
        val server = prefs.getString(KEY_REGISTERED_SERVER, null) ?: return null
        val id = prefs.getString(KEY_USER_ID, null) ?: return null
        val sealed = prefs.getString(KEY_SECRET_SEALED, null) ?: return null
        // A lost Keystore key means registering afresh.
        val secret = runCatching { SharedSecretBox.open(sealed) }.getOrNull() ?: return null
        return SharedCredentials(server, id, secret)
    }

    private fun readState() = SharedIdentityState(serverAddress, credentials != null, displayName)

    private fun publish() {
        _state.value = readState()
    }

    fun setDisplayName(name: String) {
        prefs.edit { putString(KEY_NAME, name) }
        publish()
    }

    /** Switches to [normalized] and forgets the registration. The caller wipes the local copy first. */
    fun changeServer(normalized: String) {
        synchronized(this) {
            prefs.edit {
                if (normalized == DEFAULT_SERVER) remove(KEY_SERVER) else putString(KEY_SERVER, normalized)
                remove(KEY_REGISTERED_SERVER)
                remove(KEY_USER_ID)
                remove(KEY_SECRET_SEALED)
                remove(KEY_LAST_SYNC)
            }
            registration = null
        }
        publish()
    }

    /** This phone's credentials, registering with the server first if needed (`POST /v1/device`). */
    suspend fun ensureRegistered(): SharedResult<SharedCredentials> = registerLock.withLock {
        credentials?.let { return@withLock SharedResult.Success(it) }
        val base = serverAddress.ifEmpty { return@withLock SharedResult.Failure(SharedError.NoServerAddress) }
        when (val r = remote.register(base)) {
            is RemoteResult.Ok -> {
                val c = r.value
                synchronized(this) {
                    // The address changed while this registered: the registration belongs to the old server.
                    if (serverAddress != base) return@withLock SharedResult.Failure(SharedError.Network("Server address changed"))
                    prefs.edit {
                        putString(KEY_REGISTERED_SERVER, base)
                        putString(KEY_USER_ID, c.userId)
                        putString(KEY_SECRET_SEALED, SharedSecretBox.seal(c.secret))
                    }
                    registration = c
                }
                publish()
                // Best effort: the server uses it only when a group is created without a seat name.
                displayName?.let { remote.setDisplayName(c, it) }
                SharedResult.Success(c)
            }
            is RemoteResult.Err -> SharedResult.Failure(r.error)
            is RemoteResult.Conflict -> SharedResult.Failure(SharedError.Network("Unexpected answer from the server"))
        }
    }

    companion object {
        private const val PREFS = "shared_expenses"
        private const val KEY_SERVER = "server_url"
        private const val KEY_REGISTERED_SERVER = "registered_server_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SECRET_SEALED = "secret_sealed"
        private const val KEY_NAME = "display_name"
        private const val KEY_LAST_SYNC = "last_sync_at"

        /** `sharedExpensesUrl` from local.properties, if it is an allowed address. */
        val DEFAULT_SERVER: String = SharedServerAddress.normalize(BuildConfig.SHARED_EXPENSES_URL).orEmpty()
    }
}
