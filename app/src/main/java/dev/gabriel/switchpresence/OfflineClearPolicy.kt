package dev.gabriel.switchpresence

/** Thoroughly flush RPC on initial offline detection and periodically afterwards. */
internal object OfflineClearPolicy {
    fun shouldDrainCallbacks(offlinePolls: Int): Boolean =
        offlinePolls == 1 || (offlinePolls > 1 && offlinePolls % 8 == 0)
}
