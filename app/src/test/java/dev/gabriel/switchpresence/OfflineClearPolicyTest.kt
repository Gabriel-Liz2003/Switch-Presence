package dev.gabriel.switchpresence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineClearPolicyTest {
    @Test fun firstOfflinePollUsesFullCleanup() {
        assertTrue(OfflineClearPolicy.shouldDrainCallbacks(1))
    }

    @Test fun subsequentPollsAvoidRepeatedHeavyCleanup() {
        for (poll in 2..7) assertFalse(OfflineClearPolicy.shouldDrainCallbacks(poll))
    }

    @Test fun periodicRetryMaintainsOldRpcClient() {
        assertTrue(OfflineClearPolicy.shouldDrainCallbacks(8))
        assertFalse(OfflineClearPolicy.shouldDrainCallbacks(9))
        assertTrue(OfflineClearPolicy.shouldDrainCallbacks(16))
    }
}
