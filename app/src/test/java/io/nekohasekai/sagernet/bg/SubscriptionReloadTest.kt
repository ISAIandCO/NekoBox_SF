package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionReloadTest {
    @Test fun waitsWhenLockedUntilRecoveryHasFailed() {
        assertFalse(canApplySubscriptionUpdate(true, false, true, false))
        assertTrue(canApplySubscriptionUpdate(true, false, true, true))
        assertTrue(canApplySubscriptionUpdate(false, false, true, false))
    }

    @Test fun evenAFailedGroupWaitsForNetworkAndExitFromDoze() {
        assertFalse(canApplySubscriptionUpdate(true, true, true, true))
        assertFalse(canApplySubscriptionUpdate(true, false, false, true))
    }
}
