package dev.gabriel.switchpresence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordStatusTextTest {
    @Test fun pendingRpcUnderTwentySecondsIsNotCalledFailure() {
        assertEquals(
            "Enviando atividade ao Discord",
            DiscordStatusText.describe("Enviando atividade ao Discord", 19_999L)
        )
    }

    @Test fun pendingRpcLongerThanTwentySecondsExplainsWhyPresenceIsAbsent() {
        val message = DiscordStatusText.describe("Enviando atividade ao Discord", 20_000L)
        assertTrue(message.contains("não confirmou"))
        org.junit.Assert.assertFalse(message.contains("aberto no celular"))
    }

    @Test fun confirmedSuccessIsNotOverriddenByElapsedTimeout() {
        assertEquals("Atividade publicada", DiscordStatusText.describe("Atividade publicada", 300_000L))
    }

    @Test fun sdkErrorsRemainVisible() {
        assertEquals(
            "Discord recusou: sem IPC",
            DiscordStatusText.describe("Discord recusou: sem IPC", 60_000L)
        )
    }
}
