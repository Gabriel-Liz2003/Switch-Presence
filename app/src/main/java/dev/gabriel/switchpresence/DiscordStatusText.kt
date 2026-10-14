package dev.gabriel.switchpresence

/**
 * The Discord SDK reports Rich Presence completion asynchronously.
 * "Sending" is not an acknowledgment, and the app should never suggest it is.
 */
internal object DiscordStatusText {
    fun describe(sdkStatus: String, millisecondsSincePublish: Long): String {
        if (sdkStatus == "Enviando atividade ao Discord" && millisecondsSincePublish >= 20_000L) {
            return "Discord sem resposta há mais de 20s. Confira se o Discord está aberto no celular."
        }
        return sdkStatus
    }
}
