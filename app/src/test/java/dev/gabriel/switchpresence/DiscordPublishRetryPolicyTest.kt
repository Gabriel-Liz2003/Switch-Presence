package dev.gabriel.switchpresence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordPublishRetryPolicyTest {
    @Test fun failedRpcRetriesSoonerThanTheNormalNinetySecondRefresh() {
        assertEquals(15_000L, DiscordPublishRetryPolicy.delayMs(0, 15_000L))
        assertEquals(30_000L, DiscordPublishRetryPolicy.delayMs(1, 15_000L))
        assertEquals(60_000L, DiscordPublishRetryPolicy.delayMs(2, 15_000L))
    }

    @Test fun respectsLongerServerRateLimit() {
        assertEquals(180_000L, DiscordPublishRetryPolicy.delayMs(0, 180_000L))
    }

    @Test fun avoidsRetryForPermanentFailureOrExceededBudget() {
        assertNull(DiscordPublishRetryPolicy.delayMs(0, -1L))
        assertNull(DiscordPublishRetryPolicy.delayMs(3, 15_000L))
        assertNull(DiscordPublishRetryPolicy.delayMs(-1, 15_000L))
    }
}
