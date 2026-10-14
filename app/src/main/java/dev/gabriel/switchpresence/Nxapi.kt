package dev.gabriel.switchpresence

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class SwitchGame(val title: String, val imageUrl: String, val platform: String) {
    companion object {
        fun parse(json: String): SwitchGame? {
            val root = JSONObject(json)
            val presence = root.optJSONObject("friend")?.optJSONObject("presence")
                ?: error("A resposta do nxapi não contém friend.presence")
            val state = presence.optString("state").uppercase()
            // Nintendo's presence enum includes PLAYING as well as ONLINE.
            // ONLINE without an active game is valid and produces no Discord activity.
            if (state == "OFFLINE" || state == "INACTIVE") return null
            if (state != "ONLINE" && state != "PLAYING") {
                error("Estado de presença não suportado: ${state.take(24)}")
            }
            val game = presence.optJSONObject("game") ?: return null
            val name = game.optString("name").trim()
            if (name.isBlank()) return null
            val image = game.optString("imageUri").takeIf { it.startsWith("https://") } ?: ""
            val platform = if (presence.optInt("platform") == 2) "Nintendo Switch 2" else "Nintendo Switch"
            return SwitchGame(name.take(128), image.take(500), platform)
        }
    }
}

object Nxapi {
    private const val HOST = "nxapi-presence.fancy.org.uk"

    /** Deliberate host allow-list: never fetch arbitrary URLs stored in settings. */
    fun validate(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && uri.host?.lowercase() == HOST && uri.port == -1) {
            "Cole a URL HTTPS do serviço oficial nxapi-presence."
        }
        require(uri.userInfo == null && uri.fragment == null) { "URL inválida." }
        require(Regex("^/api/presence/[A-Za-z0-9_-]{8,64}/?$").matches(uri.path ?: "")) {
            "A URL deve apontar para /api/presence/SEU_ID."
        }
        require(uri.rawQuery.isNullOrBlank() || uri.rawQuery == "include-splatoon3=1") {
            "Parâmetros de URL não suportados."
        }
        return uri.toString()
    }

    fun fetch(url: String): SwitchGame? {
        val conn = URL(validate(url)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "SwitchPresence-Android/0.1")
        try {
            val code = conn.responseCode
            if (code != 200) error("Serviço nxapi indisponível (HTTP $code)")
            require(conn.contentLengthLong in -1L..500_000L) { "Resposta nxapi grande demais." }
            val content = conn.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    text.append(buffer, 0, read)
                    require(text.length < 500_000) { "Resposta nxapi grande demais." }
                }
                text.toString()
            }
            return SwitchGame.parse(content)
        } finally {
            conn.disconnect()
        }
    }
}
