package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SubscriptionUpdateMode
import org.junit.Assert.*
import org.junit.Test

class SubscriptionUpdatePlanTest {
    @Test fun appliesFiveAndReleasesSixthAfterSwitch() {
        val changes = (1L..6L).map { SubscriptionChange(it, "Node $it", "Node $it", true) }
        val partial = planSubscriptionUpdate(
            SubscriptionUpdateMode.DEFER_ACTIVE, changes, setOf(6), emptySet(), 6,
        )
        assertEquals(setOf(6L), partial.deferredIds)
        assertEquals(setOf(6L), partial.heldIds)
        assertFalse(partial.holdAll)
        assertFalse(partial.reviewRequired)

        val remaining = changes.filter { it.profileId in partial.deferredIds }
        val afterSwitch = planSubscriptionUpdate(
            SubscriptionUpdateMode.DEFER_ACTIVE, remaining, setOf(1), emptySet(), 1,
        )
        assertEquals(SubscriptionUpdatePlan(), afterSwitch)
    }

    @Test fun holdsCriticalChangesToReferencedNodesAndAllowsCosmeticChanges() {
        val critical = SubscriptionChange(2, "Node", "Node", true)
        val cosmetic = SubscriptionChange(3, "Old name", "New name", false)
        val plan = planSubscriptionUpdate(
            SubscriptionUpdateMode.REVIEW_CRITICAL, listOf(critical, cosmetic), setOf(3), setOf(2), 3,
        )
        assertTrue(plan.holdAll)
        assertTrue(plan.reviewRequired)
        assertEquals(
            SubscriptionUpdatePlan(),
            planSubscriptionUpdate(SubscriptionUpdateMode.REVIEW_CRITICAL, listOf(cosmetic), setOf(3), setOf(2), 3),
        )
    }

    @Test fun partialKeepsReferencedDeletionForManualReview() {
        val removed = SubscriptionChange(2, "Node", null, true)
        val another = SubscriptionChange(3, "Other", "Other", true)
        val plan = planSubscriptionUpdate(
            SubscriptionUpdateMode.DEFER_ACTIVE, listOf(removed, another), setOf(3), setOf(2), 3,
        )
        assertEquals(setOf(2L, 3L), plan.heldIds)
        assertEquals(setOf(3L), plan.deferredIds)
        assertTrue(plan.reviewRequired)
    }

    @Test fun removalOfOldSelectionCanFinishWhenVpnStops() {
        val removed = SubscriptionChange(2, "Node", null, true)
        val plan = planSubscriptionUpdate(
            SubscriptionUpdateMode.DEFER_ACTIVE, listOf(removed), emptySet(), emptySet(), 2,
        )
        assertEquals(SubscriptionUpdatePlan(), plan)
    }

    @Test fun retainsNamesThatWouldCollideWithADeferredRemoval() {
        val changes = listOf(
            SubscriptionChange(1, "A", null, true),
            SubscriptionChange(2, "B", "A", false),
            SubscriptionChange(3, "C", "B", false),
        )
        val plan = planSubscriptionUpdate(
            SubscriptionUpdateMode.DEFER_ACTIVE, changes, setOf(1), emptySet(), 1,
        )
        assertEquals(setOf(1L, 2L, 3L), plan.heldIds)
        assertEquals(setOf(1L), plan.deferredIds)
    }

    @Test fun uncheckedModeAllowsEvenActiveAndReferencedRemovals() {
        val removed = SubscriptionChange(2, "Node", null, true)
        assertEquals(
            SubscriptionUpdatePlan(),
            planSubscriptionUpdate(SubscriptionUpdateMode.APPLY_IMMEDIATELY, listOf(removed), setOf(2), setOf(2), 2),
        )
    }
}
