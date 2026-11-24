package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.preference.PreferenceDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal object GeoAssetUpdater {
    private const val WORK_NAME = "GeoAssetUpdater"
    private const val CUSTOM_VERSION = "Custom"
    private val scheduleLock = Mutex()
    private val updateLock = Mutex()

    val fileNames = listOf("geoip.db", "geosite.db")
    private val providers = listOf(
        listOf("SagerNet/sing-geoip", "SagerNet/sing-geosite"),
        listOf("soffchen/sing-geoip", "soffchen/sing-geosite"),
        listOf("Chocolate4U/Iran-sing-box-rules", "Chocolate4U/Iran-sing-box-rules"),
        listOf("L11R/antizapret-sing-box-geo", "L11R/antizapret-sing-box-geo"),
    )

    private val intervalListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key == Key.RULES_UPDATE_INTERVAL) schedule()
        }
    }

    fun start() {
        DataStore.configurationStore.registerChangeListener(intervalListener)
        schedule()
    }

    private fun intervalMillis(): Long = try {
        Libcore.geoAssetUpdateIntervalMillis(DataStore.rulesUpdateInterval)
    } catch (error: Exception) {
        Logs.w("Invalid automatic GeoIP/GeoSite update interval", error)
        0
    }

    private fun schedule() = runOnDefaultDispatcher {
        scheduleLock.withLock {
            try {
                val interval = intervalMillis()
                val manager = WorkManager.getInstance(app)
                if (interval == 0L) {
                    manager.cancelUniqueWork(WORK_NAME).await()
                } else {
                    val period = maxOf(interval, PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
                    val periodTag = "$WORK_NAME:$period"
                    val scheduled = manager.getWorkInfosForUniqueWork(WORK_NAME).await()
                    if (scheduled.any { !it.state.isFinished && periodTag in it.tags }) return@withLock

                    val work = PeriodicWorkRequestBuilder<UpdateTask>(period, TimeUnit.MILLISECONDS)
                        .addTag(periodTag)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                        .build()
                    manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work).await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logs.w("Cannot schedule GeoIP/GeoSite updates", error)
            }
        }
    }

    private fun findSource(fileName: String): Source {
        val index = fileNames.indexOf(fileName)
        require(index >= 0)
        val provider = DataStore.rulesProvider
        if (provider == 4) {
            return Source(null, if (index == 0) DataStore.rulesGeoipUrl else DataStore.rulesGeositeUrl)
        }
        return Source(providers[provider][index], null)
    }

    private data class Source(val repository: String?, val url: String?)

    suspend fun importDatabase(staged: File, destination: File) = updateLock.withLock {
        Libcore.installRouteAssetVersion(staged.absolutePath, destination.absolutePath, CUSTOM_VERSION)
    }

    suspend fun update(fileName: String, automatic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        updateLock.withLock {
            if (automatic && intervalMillis() == 0L) return@withLock false
            Libcore.waitForAssets()
            val source = findSource(fileName)
            val file = File(SagerNet.application.externalAssets, fileName)
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")
            val currentVersion = if (file.isFile && versionFile.isFile) versionFile.readText().trim() else null

            // A database imported by the user takes precedence over publisher defaults.
            if (automatic && source.repository != null && file.isFile &&
                (currentVersion.isNullOrEmpty() || currentVersion == CUSTOM_VERSION)
            ) return@withLock false

            val client = Libcore.newHttpClient()
            try {
                client.modernTLS()
                client.keepAlive()
                if (DataStore.downloadsProxyOnly) {
                    client.requireSocks5(DataStore.socksPort, DataStore.mixedUsername, DataStore.mixedPassword)
                } else {
                    client.trySocks5(DataStore.socksPort, DataStore.mixedUsername, DataStore.mixedPassword)
                }

                val (version, url) = if (source.repository != null) {
                    val response = client.newRequest().apply {
                        setURL("https://api.github.com/repos/${source.repository}/releases/latest")
                    }.execute()
                    val release = JSONObject(Util.getStringBox(response.contentString))
                    val version = release.getString("tag_name")
                    check(version.isNotBlank()) { "Missing version for $fileName" }
                    if (currentVersion == version) return@withLock false

                    val assets = release.getJSONArray("assets")
                    val target = (0 until assets.length()).asSequence().map(assets::getJSONObject)
                        .firstOrNull { it.getString("name") == fileName }
                        ?: error("Missing $fileName in release $version")
                    version to target.getString("browser_download_url")
                } else {
                    CUSTOM_VERSION to checkNotNull(source.url)
                }

                val staged = File.createTempFile(".route-update-", ".tmp", file.parentFile)
                try {
                    client.newRequest().apply { setURL(url) }.execute().writeTo(staged.absolutePath)
                    currentCoroutineContext().ensureActive()
                    if (automatic && (intervalMillis() == 0L || source != findSource(fileName))) {
                        return@withLock false
                    }
                    Libcore.installRouteAssetVersion(staged.absolutePath, file.absolutePath, version)
                    Logs.i("Updated $fileName to $version")
                    true
                } finally {
                    staged.delete()
                }
            } finally {
                client.close()
            }
        }
    }

    class UpdateTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            var retry = false
            for (fileName in fileNames) {
                try {
                    update(fileName, automatic = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logs.w("Automatic update of $fileName failed", error)
                    retry = true
                }
            }
            return if (retry) Result.retry() else Result.success()
        }
    }
}
