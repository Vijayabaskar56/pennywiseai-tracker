package com.pennywiseai.tracker.data.sharedexpenses

import com.pennywiseai.tracker.domain.sharedexpenses.SharedActivity
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpense
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.domain.sharedexpenses.SharedMember
import com.pennywiseai.tracker.domain.sharedexpenses.SharedRecurring
import com.pennywiseai.tracker.domain.sharedexpenses.SharedSettlement

// The types SharedExpensesRepository hands to ViewModels. Plain Kotlin over the
// domain models: no Room, no wire types.

/** The outcome of a Shared expenses action. Expected failures are a [Failure], never an exception. */
sealed interface SharedResult<out T> {
    data class Success<out T>(val value: T) : SharedResult<T>

    data class Failure(val error: SharedError) : SharedResult<Nothing>
}

/** The server's `error` codes (docs/shared-expenses-api.md, "Errors"), plus [UNKNOWN] for anything else. */
enum class SharedServerCode(val wire: String) {
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    ALREADY_MEMBER("already_member"),
    GONE("gone"),
    INVALID("invalid"),
    BALANCE_NOT_ZERO("balance_not_zero"),
    LIMIT_REACHED("limit_reached"),
    RATE_LIMITED("rate_limited"),
    BUSY("busy"),
    INTERNAL("internal"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(code: String?, status: Int): SharedServerCode =
            entries.firstOrNull { it.wire == code && it != UNKNOWN } ?: when {
                status == 401 -> UNAUTHORIZED
                status == 403 -> FORBIDDEN
                status == 404 -> NOT_FOUND
                status == 410 -> GONE
                status == 429 -> RATE_LIMITED
                status == 503 -> BUSY
                status >= 500 -> INTERNAL
                else -> UNKNOWN
            }
    }
}

/** Why a Shared expenses action did not happen. */
sealed interface SharedError {
    /** The input breaks a rule [com.pennywiseai.tracker.domain.sharedexpenses.SharedValidation] checks; nothing was saved. */
    data class Invalid(val reason: SharedExpenseError) : SharedError

    /** No server address is set (no build default and none entered). */
    data object NoServerAddress : SharedError

    /** The address is not `https://…`, or `http://` for `localhost` / `127.0.0.1`. */
    data object InvalidServerAddress : SharedError

    /** An invite link points at [inviteServer], but this phone uses [currentServer]. Nothing was switched. */
    data class OtherServer(val inviteServer: String, val currentServer: String) : SharedError

    /** Joining or creating a group needs a display name, and none is set. */
    data object DisplayNameRequired : SharedError

    /** The group, member, row or outbox item is not on this phone (or no longer can be changed). */
    data object NotFound : SharedError

    /** "Keep mine" on a row another member deleted; only "use theirs" (or restoring first) is possible. */
    data object DeletedElsewhere : SharedError

    /** The server could not be reached or its answer could not be read. Try again later. */
    data class Network(val message: String?) : SharedError

    /** The server refused: [status] and [code] from its error body; [field] names the input for `invalid`. */
    data class Server(val status: Int, val code: SharedServerCode, val message: String, val field: String? = null) : SharedError
}

/** True for failures that go away by themselves: no network, rate limits, a busy or failing server. */
val SharedError.retryable: Boolean
    get() = this is SharedError.Network || (this is SharedError.Server && (status == 429 || status >= 500))

/** What the Shared tab needs to know about this phone's identity. */
data class SharedIdentityState(
    /** The server in use, or "" when none is set. */
    val serverAddress: String,
    /** True once this phone holds a user ID and secret for [serverAddress]. */
    val registered: Boolean,
    val displayName: String?,
) {
    val addressSet: Boolean get() = serverAddress.isNotEmpty()
}

/** Background sync status. [lastError] is the last run's failure, cleared by a run that succeeds. */
data class SharedSyncState(
    val syncing: Boolean = false,
    val lastError: SharedError? = null,
    val lastSuccessAt: Long? = null,
)

enum class SharedSyncStatus {
    /** Everything was sent and every group is up to date. */
    DONE,

    /** Stopped on a network error, a rate limit or a busy server; nothing was dropped. */
    RETRY_LATER,

    /** Stopped on an error that retrying will not fix, such as `401 unauthorized`. */
    FAILED,

    /** No server address or no registration yet, so there was nothing to sync. */
    NOT_IN_USE,
}

/**
 * One sync run. [newActivityByOthers] holds the activity rows other members
 * (or the server, for recurring expenses) made since the last sync. A group's
 * first download reports none, so joining a group does not replay its history.
 */
data class SharedSyncResult(
    val status: SharedSyncStatus,
    val error: SharedError? = null,
    val newActivityByOthers: List<SharedActivity> = emptyList(),
)

enum class SharedEntityType { EXPENSE, SETTLEMENT, RECURRING }

/** What a queued change asks the server to do. */
enum class SharedOperation { SAVE, DELETE, RESTORE }

/** An expense, Settlement or recurring expense, for places that handle all three. */
sealed interface SharedEntity {
    val id: String
    val groupId: String
    val version: Int
    val deletedAt: Long?
    val type: SharedEntityType

    data class Expense(val value: SharedExpense) : SharedEntity {
        override val id get() = value.id
        override val groupId get() = value.groupId
        override val version get() = value.version
        override val deletedAt get() = value.deletedAt
        override val type get() = SharedEntityType.EXPENSE
    }

    data class Settlement(val value: SharedSettlement) : SharedEntity {
        override val id get() = value.id
        override val groupId get() = value.groupId
        override val version get() = value.version
        override val deletedAt get() = value.deletedAt
        override val type get() = SharedEntityType.SETTLEMENT
    }

    data class Recurring(val value: SharedRecurring) : SharedEntity {
        override val id get() = value.id
        override val groupId get() = value.groupId
        override val version get() = value.version
        override val deletedAt get() = value.deletedAt
        override val type get() = SharedEntityType.RECURRING
    }
}

/** A queued change the server refused or that clashed with someone else's edit. */
sealed interface SharedOutboxProblem {
    val itemId: Long
    val groupId: String
    val entityType: SharedEntityType
    val entityId: String
    val operation: SharedOperation

    /** Refused with 403, 404 or 422 (or another 4xx). The UI shows [error] and offers to discard the change. */
    data class Failed(
        override val itemId: Long,
        override val groupId: String,
        override val entityType: SharedEntityType,
        override val entityId: String,
        override val operation: SharedOperation,
        val error: SharedError,
        /** The change as saved on this phone, null if it is gone. */
        val mine: SharedEntity?,
    ) : SharedOutboxProblem

    /**
     * Someone changed the row first. [mine] is the user's version (kept on the
     * phone), [theirs] the server's. Resolve with "keep mine" or "use theirs".
     */
    data class Conflict(
        override val itemId: Long,
        override val groupId: String,
        override val entityType: SharedEntityType,
        override val entityId: String,
        override val operation: SharedOperation,
        val mine: SharedEntity?,
        val theirs: SharedEntity,
    ) : SharedOutboxProblem
}

/** A Shared group with its seats, removed ones included (they still appear on old expenses). */
data class SharedGroupDetail(val group: SharedGroup, val members: List<SharedMember>)

/** A new Invite link. [token] is shown only once; [url] is the `https` link to share. */
data class SharedInvite(val id: String, val token: String, val url: String, val memberId: String?, val expiresAt: Long)

/** An Invite link that can still be used. [memberId] is null for an open invite. */
data class SharedInviteInfo(val id: String, val memberId: String?, val expiresAt: Long, val uses: Int)

/** What an Invite link leads to, shown before joining. */
data class SharedInvitePreview(
    val groupId: String,
    val groupName: String,
    /** The seat's name for a seat invite, null for an open invite. */
    val memberDisplayName: String?,
    val expiresAt: Long,
    val alreadyMember: Boolean,
)

/** The seat this phone holds after joining. */
data class SharedJoin(val groupId: String, val memberId: String)
