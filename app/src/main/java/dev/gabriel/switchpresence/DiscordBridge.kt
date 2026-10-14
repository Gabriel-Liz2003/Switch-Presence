package dev.gabriel.switchpresence

import android.app.Activity

/**
 * Official Discord Social SDK integration. Without the licensed AAR, the app
 * builds as a clearly labelled monitor-only preview (no fake Rich Presence).
 */
object DiscordBridge {
    val supported: Boolean = BuildConfig.HAS_DISCORD_SDK

    init {
        if (supported) System.loadLibrary("switchpresence")
    }

    fun initialize(activity: Activity): Boolean {
        if (!supported) return false
        return try {
            val clazz = Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
            clazz.getMethod("setEngineActivity", Activity::class.java).invoke(null, activity)
            true
        } catch (_: Exception) { false }
    }

    fun setup(applicationId: String): Boolean = supported && nativeSetup(applicationId)
    fun publish(game: SwitchGame, startedAtMs: Long) {
        if (supported) nativePublish(game.title, game.imageUrl, game.platform, startedAtMs)
    }
    fun clear() { if (supported) nativeClear() }
    fun pump() { if (supported) nativePump() }
    fun status(): String = if (supported) nativeLastStatus() else "SDK do Discord não incluído"

    private external fun nativeSetup(applicationId: String): Boolean
    private external fun nativePublish(title: String, image: String, platform: String, started: Long)
    private external fun nativeClear()
    private external fun nativePump()
    private external fun nativeLastStatus(): String
}
