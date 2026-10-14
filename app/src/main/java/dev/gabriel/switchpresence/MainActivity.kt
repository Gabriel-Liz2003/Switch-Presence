package dev.gabriel.switchpresence

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import android.text.InputType
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val ink = Color.WHITE
    private val muted = Color.rgb(169, 177, 192)
    private val bg = Color.rgb(16, 19, 24)
    private val surface = Color.rgb(29, 34, 41)
    private val accent = Color.rgb(230, 0, 18)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SecureSettings
    private lateinit var urlField: EditText
    private lateinit var discordField: EditText
    private lateinit var syncButton: Button
    private lateinit var status: TextView
    private lateinit var updateStatus: TextView
    private var updaterBusy = false

    private val heartbeat = object : Runnable {
        override fun run() {
            status.text = settings.status
            syncButton.text = if (settings.running) "■  PARAR MONITORAMENTO" else "▶  INICIAR MONITORAMENTO"
            syncButton.background = background(if (settings.running) surface else accent, 16)
            handler.postDelayed(this, 1600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        DiscordBridge.initialize(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(40))
        }
        scroll.addView(root)
        root.addView(label("●  SWITCH PRESENCE", accent, 13f, true))
        root.addView(space(22))
        root.addView(label("Seu jogo.\nSeu status.", ink, 36f, true))
        root.addView(space(10))
        root.addView(label("Seu Nintendo Switch no perfil do Discord. Sem PC ligado.", muted, 15f))
        root.addView(space(22))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = background(surface, 20)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        info.addView(label("●  STATUS DA CONEXÃO", accent, 12f, true))
        info.addView(space(10))
        status = label(settings.status, ink, 16f, true)
        info.addView(status)
        info.addView(space(9))
        info.addView(label("Nintendo Switch  →  nxapi  →  Discord", muted, 12f))
        root.addView(info)
        root.addView(space(15))

        if (!DiscordBridge.supported) {
            val warning = label("MODO PRÉVIA — o SDK oficial do Discord ainda não foi incluído neste APK. O jogo será detectado, mas não publicado no Discord.", Color.rgb(255, 190, 100), 13f)
            warning.setPadding(dp(12), dp(13), dp(12), dp(13))
            warning.background = background(Color.rgb(55, 44, 29), 14)
            root.addView(warning)
            root.addView(space(15))
        }
        root.addView(label("01  NINTENDO", accent, 12f, true))
        root.addView(space(11))
        root.addView(label("URL de presença nxapi", ink, 15f, true))
        root.addView(space(7))
        urlField = input("https://nxapi-presence.fancy.org.uk/api/presence/...", settings.presenceUrl)
        root.addView(urlField)
        root.addView(space(7))
        root.addView(link("Como obter a URL de presença ↗") {
            browse("https://github.com/samuelthomas2774/nxapi/discussions/155")
        })
        root.addView(space(23))

        root.addView(label("02  DISCORD", accent, 12f, true))
        root.addView(space(11))
        root.addView(label("Application ID (não é o Bot Token)", ink, 15f, true))
        root.addView(space(7))
        discordField = input("ID numérico da sua aplicação", settings.discordId)
        discordField.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(discordField)
        root.addView(space(7))
        root.addView(link("Criar aplicação no portal do Discord ↗") {
            browse("https://discord.com/developers/applications")
        })
        root.addView(space(21))

        syncButton = button("▶  INICIAR MONITORAMENTO", accent).apply {
            setOnClickListener { toggle() }
        }
        root.addView(syncButton, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(space(11))

        val test = button("TESTAR CONEXÃO NINTENDO", surface).apply { setOnClickListener { testNxapi() } }
        root.addView(test, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(space(29))

        root.addView(label("ATUALIZAÇÕES", accent, 12f, true))
        root.addView(space(8))
        root.addView(label("Versão ${BuildConfig.VERSION_NAME} · atualizações pelo GitHub Releases", muted, 13f))
        root.addView(space(11))
        root.addView(button("↻  VERIFICAR ATUALIZAÇÕES", surface).apply {
            setOnClickListener {
                if (updaterBusy) return@setOnClickListener
                updaterBusy = true
                ReleaseUpdater.check(this@MainActivity, ::setUpdateMessage) { updaterBusy = false }
            }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(space(9))
        updateStatus = label("O Android solicitará sua confirmação antes de instalar um APK novo.", muted, 12f)
        root.addView(updateStatus)
        root.addView(space(22))

        root.addView(label("O Android pode restringir a sincronização em segundo plano. Mantenha o Discord instalado e conectado neste celular. A URL nxapi fica criptografada neste dispositivo.", muted, 12f))
        root.addView(space(12))
        root.addView(link("Ver código-fonte e versões ↗") {
            browse("https://github.com/Gabriel-Liz2003/Switch-Presence/releases")
        })
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }
    override fun onPause() {
        handler.removeCallbacks(heartbeat)
        super.onPause()
    }

    private fun persist(requireDiscordId: Boolean): Boolean {
        return try {
            settings.presenceUrl = Nxapi.validate(urlField.text.toString())
            val id = discordField.text.toString().trim()
            if (requireDiscordId) require(Regex("^[0-9]{16,22}$").matches(id)) {
                "Application ID do Discord inválido."
            }
            settings.discordId = id
            true
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Verifique os dados")
                .setMessage(e.message ?: "Dados inválidos").setPositiveButton("OK", null).show()
            false
        }
    }

    private fun toggle() {
        if (settings.running) {
            stopService(Intent(this, PresenceService::class.java))
            settings.running = false
            settings.status = "Parando a sincronização..."
            return
        }
        if (!persist(DiscordBridge.supported)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, PresenceService::class.java))
        } catch (e: Exception) {
            settings.status = "Não foi possível iniciar: ${e.message ?: "permissão do Android"}"
        }
    }

    private fun testNxapi() {
        if (!persist(false)) return
        status.text = "Testando conexão..."
        val url = settings.presenceUrl
        thread(name = "nxapi-test") {
            val message = try {
                val game = Nxapi.fetch(url)
                if (game == null) "Conexão OK. Switch offline/sem jogo."
                else "Conexão OK: ${game.title} (${game.platform})"
            } catch (e: Exception) {
                "Falha ao consultar nxapi: ${e.message ?: "conexão"}"
            }
            runOnUiThread {
                settings.status = message
                status.text = message
            }
        }
    }

    private fun setUpdateMessage(message: String) {
        runOnUiThread { if (::updateStatus.isInitialized) updateStatus.text = message }
    }

    private fun browse(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun input(hint: String, initial: String): EditText = EditText(this).apply {
        setSingleLine(true)
        setText(initial)
        setHint(hint)
        textSize = 14f
        setTextColor(ink)
        setHintTextColor(muted)
        background = background(surface, 13)
        setPadding(dp(15), 0, dp(15), 0)
        minHeight = dp(55)
    }
    private fun label(text: String, color: Int, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun link(text: String, callback: () -> Unit): TextView =
        label(text, Color.rgb(200, 208, 223), 13f).apply {
            setOnClickListener { callback() }
            setPadding(0, dp(7), 0, dp(7))
        }
    private fun button(text: String, color: Int) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        background = background(color, 16)
        gravity = Gravity.CENTER
        elevation = 0f
    }
    private fun background(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun space(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
