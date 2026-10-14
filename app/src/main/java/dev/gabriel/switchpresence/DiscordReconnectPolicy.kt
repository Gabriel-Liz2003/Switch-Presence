package dev.gabriel.switchpresence

/** Avoid constantly restarting RPC while still recovering dropped background transports. */
internal object DiscordReconnectPolicy {
    fun shouldRefresh(offlinePolls: Int): Boolean =
        offlinePolls == 2 || (offlinePolls > 2 && offlinePolls % 8 == 0)
}
