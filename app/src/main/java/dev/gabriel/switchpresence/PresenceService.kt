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
    private var gameStartedAt = 0L
    @Volatile private var lastPublishedElapsed = 0L
    @Volatile private var activeGameSummary: String? = null
    private var errors = 0
    private val stopHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    private lateinit var settings: SecureSettings

    override fun onBind(intent: Intent?): IBinder? = null

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
                    activeGameSummary = null

                    if (DiscordBridge.supported) {
                        try {
                            callbacks.execute {
                                try {
                                    DiscordBridge.clear()
                                    // Pump RPC after ClearRichPresence, without blocking service teardown.
                                    repeat(3) {
                                        DiscordBridge.pump()
                                        Thread.sleep(150)
                                    }
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
            ACTION_CLEAR -> {
                if (pollTask != null && !stopping) {
                    clearDiscord("Limpeza manual solicitada ao Discord")
                    // If still online the following poll may legitimately publish again.
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
                    activeGameSummary = null
                    lastGame = null
                    gameStartedAt = 0L
                    lastPublishedElapsed = 0L
                    // Retry the clear in the background on every offline poll, even
                    // after an app restart. ClearRichPresence() has no callback;
                    // do not imply that the app is waiting for Discord to respond.
                    if (DiscordBridge.supported) {
                        try {
                            DiscordBridge.clear()
                            settings.status = "Switch offline • aguardando próximo jogo"
                        } catch (error: Exception) {
                            settings.status = "Switch offline • falha ao limpar Discord: ${error.message ?: "erro"}"
                        }
                    } else {
                        settings.status = "Switch offline • aguardando próximo jogo"
                    }
                } else {
                    activeGameSummary = "Jogando: ${game.title} • ${game.platform}"
                    val changed = game.title != lastGame
                    if (changed) {
                        lastGame = game.title
                        gameStartedAt = System.currentTimeMillis()
                    }
                    if (DiscordBridge.supported && (changed ||
                            SystemClock.elapsedRealtime() - lastPublishedElapsed > 90_000)) {
                        DiscordBridge.publish(game, gameStartedAt)
                        lastPublishedElapsed = SystemClock.elapsedRealtime()
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
                if (errors >= 3) {
                    activeGameSummary = null
                    lastGame = null
                    gameStartedAt = 0L
                    lastPublishedElapsed = 0L
                    if (DiscordBridge.supported) {
                        clearDiscord("Sem dados Nintendo: limpeza solicitada ao Discord")
                    }
                }
            }
        }, 0, 45, TimeUnit.SECONDS)
        return START_STICKY
    }

    private fun clearDiscord(label: String) {
        try {
            if (DiscordBridge.supported) {
                DiscordBridge.clear()
                settings.status = "$label (sem confirmação do Discord)"
            }
        } catch (e: Exception) {
            settings.status = "Falha ao limpar Discord: ${e.message ?: "desconhecida"}"
        }
    }

    private fun startForegroundNotice() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, "presence")
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Switch Presence")
            .setContentText("Monitorando o Nintendo Switch")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(30, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(30, notice)
    }

    override fun onDestroy() {
        stopping = true
        activeGameSummary = null
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
                        DiscordBridge.clear()
                        DiscordBridge.pump()
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
    }
}
