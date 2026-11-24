package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SubscriptionUpdateMode

internal enum class SubscriptionUpdateResult {
    APPLIED, NEEDS_REVIEW, DEFERRED, SKIPPED, RETRY,
}

internal data class SubscriptionChange(
    val profileId: Long,
    val oldName: String,
    val newName: String?,
    val requiresProtection: Boolean,
)

internal data class SubscriptionUpdatePlan(
    val reviewRequired: Boolean = false,
    val deferredIds: Set<Long> = emptySet(),
    val heldIds: Set<Long> = emptySet(),
    val holdAll: Boolean = false,
)

internal fun planSubscriptionUpdate(
    mode: Int,
    changes: List<SubscriptionChange>,
    activeIds: Set<Long>,
    configuredReferences: Set<Long>,
    selectedId: Long,
): SubscriptionUpdatePlan {
    if (mode == SubscriptionUpdateMode.APPLY_IMMEDIATELY) return SubscriptionUpdatePlan()
    if (mode != SubscriptionUpdateMode.DEFER_ACTIVE) {
        val review = changes.any {
            it.requiresProtection && (it.profileId == selectedId ||
                it.profileId in activeIds || it.profileId in configuredReferences)
        }
        return SubscriptionUpdatePlan(reviewRequired = review, holdAll = review)
    }

    val deferred = changes.filter { it.requiresProtection && it.profileId in activeIds }
    val review = changes.filter {
        it.newName == null && it.profileId in configuredReferences
    }
    val held = (deferred + review).associateBy { it.profileId }.toMutableMap()

    // A retained node can keep a name another node was about to take.
    val incoming = changes.filter { it.newName != null }.associateBy { it.newName }
    val reserved = ArrayDeque(held.values)
    while (reserved.isNotEmpty()) {
        val collision = incoming[reserved.removeFirst().oldName] ?: continue
        if (held.putIfAbsent(collision.profileId, collision) == null) reserved.add(collision)
    }
    return SubscriptionUpdatePlan(review.isNotEmpty(), deferred.map { it.profileId }.toSet(), held.keys)
}
