package io.nekohasekai.sagernet.bg

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class SubscriptionRuntime(
    val state: BaseService.State,
    val activeIds: Set<Long>,
) {
    val isTransitioning get() = state == BaseService.State.Connecting || state == BaseService.State.Stopping

    companion object {
        val uri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.subscription-runtime")
        const val SNAPSHOT = "snapshot"
        const val STATE = "state"
        const val ACTIVE_IDS = "activeIds"

        suspend fun read(): SubscriptionRuntime = withContext(Dispatchers.IO) {
            val snapshot = checkNotNull(app.contentResolver.call(uri, SNAPSHOT, null, null))
            SubscriptionRuntime(
                BaseService.State.values()[snapshot.getInt(STATE)],
                checkNotNull(snapshot.getLongArray(ACTIVE_IDS)).toSet(),
            )
        }
    }
}

/** Runs in the VPN service process and returns only local profile IDs and connection state. */
class SubscriptionRuntimeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == SubscriptionRuntime.SNAPSHOT)
        val state = DataStore.serviceState
        val ids = if (state.connected) {
            val proxy = checkNotNull(DataStore.baseService?.data?.proxy)
            val config = proxy.config
            val root = config.mainOutboundTag
            buildSet {
                if (config.selectorGroupId <= 0) add(proxy.profile.id)
                if (root != null) {
                    val tags = JSONArray(proxy.box.activeOutboundTags(root))
                    for (i in 0 until tags.length()) {
                        config.trafficMap[tags.getString(i)].orEmpty().forEach { add(it.id) }
                    }
                }
            }
        } else emptySet()
        return Bundle().apply {
            putInt(SubscriptionRuntime.STATE, state.ordinal)
            putLongArray(SubscriptionRuntime.ACTIVE_IDS, ids.toLongArray())
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
