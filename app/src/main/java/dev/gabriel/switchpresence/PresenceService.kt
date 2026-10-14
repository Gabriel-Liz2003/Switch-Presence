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
    private var lastPublishedElapsed = 0L
    private var offlineClearAttempts = 0
    private var errors = 0
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
                    stopping = true
                    pollTask?.cancel(true)
                    settings.status = "Parando: pedindo remoção da atividade no Discord..."
                    // Keep the SDK callback pump alive briefly before terminating the service.
                    clearDiscord("Parando: limpeza solicitada ao Discord")
                    Handler(Looper.getMainLooper()).postDelayed({ stopSelf(startId) }, 1_500L)
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

        settings.running = true
        settings.status = if (DiscordBridge.supported) "Conectando à Nintendo e ao Discord..."
                          else "Modo prévia: lendo Nintendo sem publicar no Discord."
        if (DiscordBridge.supported) {
            callbackTask = callbacks.scheduleWithFixedDelay({
                try { DiscordBridge.pump() } catch (_: Exception) {}
            }, 0, 500, TimeUnit.MILLISECONDS)
        }
        pollTask = polling.scheduleWithFixedDelay({
            if (stopping) return@scheduleWithFixedDelay
            try {
                val game = Nxapi.fetch(url)
                if (stopping) return@scheduleWithFixedDelay
                errors = 0
                if (game == null) {
                    lastGame = null
                    gameStartedAt = 0L
                    lastPublishedElapsed = 0L
                    // IMPORTANT: clear even after an app restart while already offline.
                    // Reissue while offline because mobile RPC may lose a request.
                    offlineClearAttempts++
                    if (DiscordBridge.supported) {
                        clearDiscord("Console offline • pedido de remoção #$offlineClearAttempts")
                    } else {
                        settings.status = "Console offline ou nenhum jogo aberto."
                    }
                } else {
                    offlineClearAttempts = 0
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
                    settings.status = (if (DiscordBridge.supported) "Jogando" else "Prévia") +
                        ": ${game.title} • ${game.platform}" +
                        (if (DiscordBridge.supported) " • ${DiscordBridge.status()}" else "")
                }
            } catch (error: Exception) {
                errors++
                settings.status = "Falha nxapi ($errors): ${error.message ?: "sem conexão"}"
                if (errors >= 3) {
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
        pollTask?.cancel(true)
        polling.shutdownNow()
        try {
            DiscordBridge.clear()
            // Dispatch queued native callbacks once before leaving.
            DiscordBridge.pump()
        } catch (_: Exception) {}
        callbackTask?.cancel(true)
        callbacks.shutdownNow()
        settings.running = false
        settings.status = "Monitoramento parado (limpeza solicitada ao Discord)."
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.gabriel.switchpresence.STOP"
        const val ACTION_CLEAR = "dev.gabriel.switchpresence.CLEAR"
    }
}
