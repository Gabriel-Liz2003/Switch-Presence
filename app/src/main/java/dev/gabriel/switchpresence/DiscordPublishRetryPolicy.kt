package dev.gabriel.switchpresence

/**
 * Respect the SDK's retry guidance and add bounded backoff so transient
 * Android RPC failures don't wait for the next 90-second refresh.
 */
internal object DiscordPublishRetryPolicy {
    private val localBackoffMs = longArrayOf(15_000L, 30_000L, 60_000L)

    /** Null means either an unretryable result or the retry budget is exhausted. */
    fun delayMs(previousRetries: Int, sdkRecommendedMs: Long): Long? {
        if (previousRetries !in localBackoffMs.indices || sdkRecommendedMs < 0L) return null
        return maxOf(localBackoffMs[previousRetries], sdkRecommendedMs)
    }
}
