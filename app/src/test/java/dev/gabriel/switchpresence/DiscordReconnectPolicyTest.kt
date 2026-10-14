package dev.gabriel.switchpresence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordReconnectPolicyTest {
    @Test fun firstOfflinePollClearsUsingCurrentClient() {
        assertFalse(DiscordReconnectPolicy.shouldRefresh(1))
    }

    @Test fun secondOfflinePollRepairsStaleTransport() {
        assertTrue(DiscordReconnectPolicy.shouldRefresh(2))
    }

    @Test fun reconnectionIsRateLimited() {
        for (poll in 3..7) assertFalse(DiscordReconnectPolicy.shouldRefresh(poll))
        assertTrue(DiscordReconnectPolicy.shouldRefresh(8))
        assertFalse(DiscordReconnectPolicy.shouldRefresh(9))
        assertTrue(DiscordReconnectPolicy.shouldRefresh(16))
    }
}
