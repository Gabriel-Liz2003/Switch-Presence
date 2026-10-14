package dev.gabriel.switchpresence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Poll Nintendo while a user-controlled foreground service is active.
 * Clear requests are best-effort: Discord's ClearRichPresence() has no completion
 * callback, so never mark removal as confirmed by Discord.
 */
class PresenceService : Service() {
    private val polling = Executors.newSingleThreadScheduledExecutor()
    private val callbacks = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    private var callbackTask: ScheduledFuture<*>? = null
    private var lastGame: String? = null
    @Volatile private var gameStartedAt = 0L
    @Volatile private var lastActiveGame: SwitchGame? = null
    @Volatile private var lastPublishedElapsed = 0L
    @Volatile private var activeGameSummary: String? = null
    private var errors = 0
    private var offlinePolls = 0
    private val presenceGeneration = AtomicLong(0L)
    private val stopHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    private lateinit var settings: SecureSettings

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The SDK is owned by the long-lived foreground service, never the Activity.
     * Run all native RPC requests and RunCallbacks() on the same executor so
     * callback processing does not race a publish/clear/reconnect request.
     */
    private fun enqueueDiscord(action: () -> Unit) {
        if (!DiscordBridge.supported || callbacks.isShutdown) return
        try {
            callbacks.execute {
                try {
                    action()
                    DiscordBridge.pump()
                } catch (e: Exception) {
                    if (!stopping) settings.status =
                        "Discord indisponível: ${e.message ?: "falha de comunicação"}"
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /**
     * Match the successful stop-monitoring path: clear twice and pump callbacks
     * between requests while keeping the original native RPC client alive.
     * ClearRichPresence() has no completion callback.
     * This method must run on the callbacks executor.
     */
    private fun clearDiscordWithDrain() {
        if (!DiscordBridge.supported) return
        repeat(2) {
            DiscordBridge.clear()
            repeat(3) {
                DiscordBridge.pump()
                Thread.sleep(150L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettings(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("presence", "Presença do Nintendo Switch", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (!stopping) {
                    // Stop immediately in the UI and cancel any pending Nintendo polling.
                    // Do not run the native SDK's potentially blocking mutex on the main thread.
                    stopping = true
                    isActive = false
                    settings.running = false
                    settings.status = "Monitoramento parado; remoção solicitada ao Discord."
                    pollTask?.cancel(true)
                    pollTask = null
                    presenceGeneration.incrementAndGet()
                    activeGameSummary = null
                    lastActiveGame = null

                    if (DiscordBridge.supported) {
                        try {
                            callbacks.execute {
                                try {
                                    clearDiscordWithDrain()
                                } catch (_: Exception) {
                                    // Discord's clear API has no success callback.
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    // Use stopSelf() rather than stopSelf(startId): additional start intents
                    // must not keep an otherwise stopped foreground service alive.
                    stopHandler.postDelayed({ stopSelf() }, 1_200L)
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                if (pollTask != null && !stopping && DiscordBridge.supported) {
                    val generation = presenceGeneration.incrementAndGet()
                    settings.status = "Reconectando o Discord..."
                    enqueueDiscord {
                        if (!DiscordBridge.setup(settings.discordId)) {
                            settings.status = "Falha ao reiniciar a conexão Discord"
                        } else if (!stopping && generation == presenceGeneration.get()) {
                            val active = lastActiveGame
                            if (active == null) {
                                clearDiscordWithDrain()
                                settings.status = "Switch offline • limpeza solicitada ao Discord"
                            } else {
                                // A reconnect while playing must republish, not clear the game.
                                lastPublishedElapsed = SystemClock.elapsedRealtime()
                                DiscordBridge.publish(active, gameStartedAt)
                            }
                        }
                    }
                }
                return START_STICKY
            }
            ACTION_CLEAR -> {
                if (pollTask != null && !stopping) {
                    presenceGeneration.incrementAndGet()
                    // Clear using the original native RPC client (no premature reset).
                    enqueueDiscord {
                        clearDiscordWithDrain()
                        settings.status = if (activeGameSummary == null)
                            "Switch offline • limpeza solicitada ao Discord"
                        else "Limpeza solicitada; um jogo ativo pode reaparecer"
                    }
                    lastGame = null
                    lastPublishedElapsed = 0L
                }
                return START_STICKY
            }
        }
        if (stopping) return START_NOT_STICKY
        if (pollTask != null) return START_STICKY
        startForegroundNotice()

        val url = try {
            Nxapi.validate(settings.presenceUrl)
        } catch (_: Exception) {
            settings.status = "Configure uma URL válida do nxapi."
            stopSelf()
            return START_NOT_STICKY
        }
        if (DiscordBridge.supported && !DiscordBridge.setup(settings.discordId)) {
            settings.status = "Application ID do Discord inválido."
            stopSelf()
            return START_NOT_STICKY
        }

        isActive = true
        offlinePolls = 0
        settings.running = true
        settings.status = if (DiscordBridge.supported) "Conectando à Nintendo e ao Discord..."
                          else "Modo prévia: lendo Nintendo sem publicar no Discord."
        if (DiscordBridge.supported) {
            callbackTask = callbacks.scheduleWithFixedDelay({
                try {
                    DiscordBridge.pump()
                    // Rich Presence results are delivered later, not during publish().
                    // Surface the callback result without waiting for the 45-second nxapi poll.
                    val summary = activeGameSummary
                    if (!stopping && summary != null) {
                        val elapsed = (SystemClock.elapsedRealtime() - lastPublishedElapsed).coerceAtLeast(0L)
                        val message = "$summary • " +
                            DiscordStatusText.describe(DiscordBridge.status(), elapsed)
                        if (settings.status != message) settings.status = message
                    }
                } catch (_: Exception) {}
            }, 0, 1, TimeUnit.SECONDS)
        }
        pollTask = polling.scheduleWithFixedDelay({
            if (stopping) return@scheduleWithFixedDelay
            try {
                val game = Nxapi.fetch(url)
                if (stopping) return@scheduleWithFixedDelay
                errors = 0
                if (game == null) {
                    presenceGeneration.incrementAndGet()
                    offlinePolls++
                    activeGameSummary = null
                    lastActiveGame = null
                    lastGame = null
                    gameStartedAt = 0L
                    lastPublishedElapsed = 0L
                    // Retry the clear in the background on every offline poll, even
                    // after an app restart. ClearRichPresence() has no callback;
                    // do not imply that the app is waiting for Discord to respond.
                    settings.status = "Switch offline • aguardando próximo jogo"
                    updateForegroundNotice("Switch offline • checando Discord")
                    if (DiscordBridge.supported) {
                        val firstOfflinePoll = offlinePolls == 1
                        enqueueDiscord {
                            // Do not replace the Client every few offline polls: it may
                            // still own the activity we are trying to remove.
                            if (firstOfflinePoll) clearDiscordWithDrain()
                            else DiscordBridge.clear()
                        }
                    }
                } else {
                    updateForegroundNotice("Jogando: ${game.title.take(70)}")
                    offlinePolls = 0
                    activeGameSummary = "Jogando: ${game.title} • ${game.platform}"
                    lastActiveGame = game
                    val changed = game.title != lastGame
                    if (changed) {
                        lastGame = game.title
                        gameStartedAt = System.currentTimeMillis()
                    }
                    if (DiscordBridge.supported && (changed ||
                            SystemClock.elapsedRealtime() - lastPublishedElapsed > 90_000)) {
                        val generation = presenceGeneration.incrementAndGet()
                        val started = gameStartedAt
                        lastPublishedElapsed = SystemClock.elapsedRealtime()
                        enqueueDiscord {
                            // A late publish after an offline poll would reintroduce stale activity.
                            if (!stopping && generation == presenceGeneration.get()) {
                                DiscordBridge.publish(game, started)
                            }
                        }
                    }
                    val summary = activeGameSummary.orEmpty()
                    settings.status = if (DiscordBridge.supported) {
                        val elapsed = (SystemClock.elapsedRealtime() - lastPublishedElapsed).coerceAtLeast(0L)
                        "$summary • ${DiscordStatusText.describe(DiscordBridge.status(), elapsed)}"
                    } else {
                        "Prévia: ${game.title} • ${game.platform}"
                    }
                }
            } catch (error: Exception) {
                if (stopping) return@scheduleWithFixedDelay
                errors++
                settings.status = "Falha nxapi ($errors): ${error.message ?: "sem conexão"}"
                updateForegroundNotice("Falha ao consultar o nxapi • tentativa $errors")
                if (errors >= 3) {
                    activeGameSummary = null
                    lastActiveGame = null
                    lastGame = null
                    gameStartedAt = 0L
                    lastPublishedElapsed = 0L
                    presenceGeneration.incrementAndGet()
                    if (DiscordBridge.supported) {
                        enqueueDiscord { DiscordBridge.clear() }
                    }
                }
            }
        }, 0, 45, TimeUnit.SECONDS)
        return START_STICKY
    }

    private fun foregroundNotification(detail: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, javaClass).setAction(ACTION_STOP), flags
        )
        val repair = PendingIntent.getService(
            this, 2, Intent(this, javaClass).setAction(ACTION_RECONNECT), flags
        )
        return Notification.Builder(this, "presence")
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Switch Presence")
            .setContentText(detail.take(100))
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stop)
            .addAction(android.R.drawable.ic_menu_rotate, "Reconectar Discord", repair)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotice(detail: String) {
        try {
            val time = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis())
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(30, foregroundNotification("$detail • $time"))
        } catch (_: Exception) { /* Notification permission may be denied. */ }
    }

    private fun startForegroundNotice() {
        val notice = foregroundNotification("Iniciando monitoramento...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(30, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(30, notice)
    }

    override fun onDestroy() {
        stopping = true
        activeGameSummary = null
        lastActiveGame = null
        isActive = false
        // Mark stopped BEFORE closing executors; native Discord calls must not block UI state.
        settings.running = false
        settings.status = "Monitoramento parado. A remoção no Discord foi solicitada."
        pollTask?.cancel(true)
        polling.shutdownNow()
        if (DiscordBridge.supported) {
            try {
                callbacks.execute {
                    try {
                        clearDiscordWithDrain()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        callbackTask?.cancel(false)
        // Avoid blocking the Android main thread waiting for native RPC shutdown.
        callbacks.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile var isActive = false
            private set
        const val ACTION_STOP = "dev.gabriel.switchpresence.STOP"
        const val ACTION_CLEAR = "dev.gabriel.switchpresence.CLEAR"
        const val ACTION_RECONNECT = "dev.gabriel.switchpresence.RECONNECT"
    }
}
