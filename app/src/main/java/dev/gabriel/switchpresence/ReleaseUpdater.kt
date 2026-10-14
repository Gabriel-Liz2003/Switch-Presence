package dev.gabriel.switchpresence

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Opt-in APK updates. Only the canonical repository's latest stable GitHub Release
 * is accepted. A matching SHA-256 asset is mandatory and installation uses the OS UI.
 */
object ReleaseUpdater {
    private const val API = "https://api.github.com/repos/Gabriel-Liz2003/Switch-Presence/releases/latest"
    private const val DOWNLOAD_PREFIX =
        "https://github.com/Gabriel-Liz2003/Switch-Presence/releases/download/"
    private const val MAX_APK_BYTES = 150L * 1024 * 1024

    private data class Release(val version: String, val apk: String, val checksum: String)

    fun check(activity: Activity, status: (String) -> Unit, finished: () -> Unit) {
        status("Procurando nova versão no GitHub...")
        thread(name = "release-check") {
            try {
                val release = latest()
                activity.runOnUiThread {
                    if (compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) {
                        status("Você já está na versão mais recente (${BuildConfig.VERSION_NAME}).")
                        finished()
                    } else {
                        status("Nova versão ${release.version} disponível.")
                        AlertDialog.Builder(activity)
                            .setTitle("Atualização disponível")
                            .setMessage("Instalada: ${BuildConfig.VERSION_NAME}\nNova: ${release.version}\n\nBaixar e instalar pelo Android?")
                            .setNegativeButton("Cancelar") { _, _ -> finished() }
                            .setPositiveButton("Baixar") { _, _ ->
                                download(activity, release, status, finished)
                            }
                            .setOnCancelListener { finished() }
                            .show()
                    }
                }
            } catch (e: Exception) {
                status("Não foi possível verificar: ${e.message ?: "sem conexão"}")
                activity.runOnUiThread { finished() }
            }
        }
    }

    private fun latest(): Release {
        val root = JSONObject(readText(API, 250_000))
        require(!root.optBoolean("draft") && !root.optBoolean("prerelease")) {
            "Nenhuma versão estável publicada"
        }
        val tag = root.getString("tag_name")
        require(Regex("^v?[0-9]+(\\.[0-9]+){1,3}$").matches(tag)) { "Tag de versão inválida" }
        val assets = root.getJSONArray("assets")
        var apk = ""
        var checksum = ""
        for (i in 0 until assets.length()) {
            val item = assets.getJSONObject(i)
            when (item.optString("name")) {
                "SwitchPresence.apk" -> apk = item.optString("browser_download_url")
                "SwitchPresence.sha256" -> checksum = item.optString("browser_download_url")
            }
        }
        require(apk.startsWith(DOWNLOAD_PREFIX) && checksum.startsWith(DOWNLOAD_PREFIX)) {
            "A Release não contém APK e checksum oficiais"
        }
        return Release(tag.removePrefix("v"), apk, checksum)
    }

    private fun download(activity: Activity, release: Release, status: (String) -> Unit,
                         finished: () -> Unit) {
        status("Baixando APK ${release.version}...")
        thread(name = "release-download") {
            try {
                val expected = readText(release.checksum, 1024).trim().split(Regex("\\s+")).first()
                require(Regex("^[a-fA-F0-9]{64}$").matches(expected)) { "Checksum SHA-256 inválido" }

                val folder = File(activity.cacheDir, "updates").apply { mkdirs() }
                val target = File(folder, "SwitchPresence.apk")
                val conn = connection(release.apk)
                try {
                    require(conn.responseCode == 200) { "APK: HTTP ${conn.responseCode}" }
                    require(conn.contentLengthLong in -1L..MAX_APK_BYTES) { "APK muito grande" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    conn.inputStream.use { source ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= MAX_APK_BYTES) { "APK excede o limite" }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    require(count > 0) { "Arquivo APK vazio" }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    require(expected.equals(actual, ignoreCase = true)) { "SHA-256 não confere; instalação bloqueada" }
                } catch (error: Exception) {
                    target.delete()
                    throw error
                } finally {
                    conn.disconnect()
                }
                activity.runOnUiThread {
                    status("APK conferido. Confirme a instalação no Android.")
                    install(activity, target, status)
                    finished()
                }
            } catch (e: Exception) {
                status("Falha na atualização: ${e.message ?: "download"}")
                activity.runOnUiThread { finished() }
            }
        }
    }

    private fun install(activity: Activity, apk: File, status: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            status("Autorize instalação de apps desta fonte e verifique novamente.")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity,
            "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun readText(url: String, max: Int): String {
        val conn = connection(url)
        try {
            require(conn.responseCode == 200) { "GitHub HTTP ${conn.responseCode}" }
            require(conn.contentLengthLong in -1L..max.toLong()) { "Resposta muito grande" }
            conn.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    text.append(chunk, 0, read)
                    require(text.length <= max) { "Resposta muito grande" }
                }
                return text.toString()
            }
        } finally { conn.disconnect() }
    }

    private fun connection(url: String): HttpURLConnection {
        require(url.startsWith(API) || url.startsWith(DOWNLOAD_PREFIX)) { "Endereço de atualização inválido" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", if (url == API) "application/vnd.github+json" else "*/*")
            setRequestProperty("User-Agent", "SwitchPresence-Updater/${BuildConfig.VERSION_NAME}")
            if (url == API) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val left = a.removePrefix("v").split('.').map { it.toLongOrNull() ?: 0L }
        val right = b.removePrefix("v").split('.').map { it.toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(left.size, right.size)) {
            val compare = (left.getOrElse(i) { 0L }).compareTo(right.getOrElse(i) { 0L })
            if (compare != 0) return compare
        }
        return 0
    }
}
