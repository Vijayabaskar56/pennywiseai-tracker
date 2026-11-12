package com.pennywiseai.tracker.data.sharedexpenses

import java.net.URI
import java.net.URLDecoder

/** Rules for the Shared expenses server address (docs/shared-expenses.md, "Security"). */
object SharedServerAddress {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")

    /**
     * [input] as `scheme://host[:port][/path]` without a trailing slash, or null
     * when it is not allowed: it must be `https://`, or `http://` only for
     * `localhost` and `127.0.0.1`, with no user info, query or fragment.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val allowed = when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host in LOCAL_HOSTS
            else -> false
        }
        if (!allowed) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme.lowercase()}://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }
}

/** A server address and token taken from an Invite link. */
data class SharedInviteLink(val server: String, val token: String) {
    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

        /**
         * Reads `pennywise://shared/join?server=…&token=…` (the button on the
         * join page) or `https://<server>/join/<token>` (the link people share).
         * Null when [text] is neither or the server address is not allowed.
         */
        fun parse(text: String): SharedInviteLink? {
            val trimmed = text.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val (server, token) = when {
                uri.scheme.equals("pennywise", ignoreCase = true) -> {
                    if (uri.host != "shared" || uri.path != "/join") return null
                    val query = uri.rawQuery?.split('&').orEmpty().mapNotNull { part ->
                        val i = part.indexOf('=')
                        if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
                    }.toMap()
                    (query["server"] ?: return null) to (query["token"] ?: return null)
                }
                else -> {
                    val i = trimmed.lastIndexOf("/join/")
                    if (i <= 0) return null
                    trimmed.substring(0, i) to trimmed.substring(i + "/join/".length).trimEnd('/')
                }
            }
            if (!TOKEN.matches(token)) return null
            val normalized = SharedServerAddress.normalize(server) ?: return null
            return SharedInviteLink(normalized, token)
        }
    }
}
