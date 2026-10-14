package dev.gabriel.switchpresence

/**
 * A Rich Presence update sometimes never completes on Android.
 * The manual "Reconectar Discord" action fixes this case on the user's device.
 *
 * Recover the same way automatically, but only for an active game's unanswered
 * publication. Limit native SDK client restarts to two attempts per game until
 * a successful publish callback (or a different game is detected).
 */
internal object DiscordTimeoutRecoveryPolicy {
    const val MAX_RECONNECTS = 2
    private const val PENDING = "Enviando atividade ao Discord"
    private val timeoutMs = longArrayOf(35_000L, 60_000L)

    fun shouldReconnect(sdkStatus: String, elapsedSincePublishMs: Long, priorReconnects: Int): Boolean =
        sdkStatus == PENDING &&
            priorReconnects in 0 until MAX_RECONNECTS &&
            elapsedSincePublishMs >= timeoutMs[priorReconnects]
}
