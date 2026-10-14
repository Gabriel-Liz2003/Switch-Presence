package dev.gabriel.switchpresence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordTimeoutRecoveryPolicyTest {
    @Test fun reconnectWhenFirstPublishNeverCompletes() {
        assertFalse(DiscordTimeoutRecoveryPolicy.shouldReconnect("Enviando atividade ao Discord", 34_999L, 0))
        assertTrue(DiscordTimeoutRecoveryPolicy.shouldReconnect("Enviando atividade ao Discord", 35_000L, 0))
    }

    @Test fun retriesAreRateLimitedAndBounded() {
        assertFalse(DiscordTimeoutRecoveryPolicy.shouldReconnect("Enviando atividade ao Discord", 59_999L, 1))
        assertTrue(DiscordTimeoutRecoveryPolicy.shouldReconnect("Enviando atividade ao Discord", 60_000L, 1))
        assertFalse(DiscordTimeoutRecoveryPolicy.shouldReconnect("Enviando atividade ao Discord", 500_000L, 2))
        assertFalse(DiscordTimeoutRecoveryPolicy.shouldReconnect("Enviando atividade ao Discord", 500_000L, -1))
    }

    @Test fun neverReconnectWhenSdkAlreadyAnswered() {
        for (status in listOf("Atividade publicada", "Discord recusou: RPCError (code 0)", "Pedido de remoção enviado")) {
            assertFalse(DiscordTimeoutRecoveryPolicy.shouldReconnect(status, 500_000L, 0))
        }
    }
}
