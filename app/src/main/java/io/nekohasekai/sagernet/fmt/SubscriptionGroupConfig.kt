package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.SagerDatabase

internal data class SubscriptionProbeGroup(
    val candidateTags: List<String>,
    val subscriptionIds: Set<Long>,
)

internal fun subscriptionProfileRevisions(): Map<Long, Long> =
    SagerDatabase.groupDao.allGroups().mapNotNull { group ->
        group.subscription?.let { group.id to it.profileRevision }
    }.toMap()

internal fun ConfigBuildResult.hasSameCandidateConfig(
    tag: String, newConfig: ConfigBuildResult, newTag: String,
): Boolean {
    val chain = trafficMap[tag].orEmpty()
    val newChain = newConfig.trafficMap[newTag].orEmpty()
    if (chain.size != newChain.size || chain.indices.any { index ->
            val old = chain[index]
            val new = newChain[index]
            old.id != new.id || old.groupId != new.groupId || old.requireBean() != new.requireBean()
        }) return false

    val candidates = subscriptionProbeGroups[tag]?.candidateTags.orEmpty()
    val newCandidates = newConfig.subscriptionProbeGroups[newTag]?.candidateTags.orEmpty()
    return candidates.size == newCandidates.size && candidates.indices.all { index ->
        hasSameCandidateConfig(candidates[index], newConfig, newCandidates[index])
    }
}
