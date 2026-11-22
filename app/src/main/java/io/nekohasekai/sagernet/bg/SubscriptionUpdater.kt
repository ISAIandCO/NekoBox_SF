package io.nekohasekai.sagernet.bg

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionUpdateMode
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.SubscriptionUpdateResult
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val DEFERRED_WORK = "DeferredSubscriptionUpdates"
    private const val DEFERRED_ONLY = "deferredOnly"
    private const val REVIEW_NOTIFICATION = 1
    private const val CHANNEL = "service-subscription"

    private val collectingSelectionChanges = AtomicBoolean()
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    suspend fun reconfigureUpdater() {
        val subscriptions = SagerDatabase.groupDao.subscriptions().mapNotNull { it.subscription }
            .filter { it.autoUpdate }
        val manager = RemoteWorkManager.getInstance(app)
        if (subscriptions.isEmpty()) {
            manager.cancelUniqueWork(WORK_NAME)
            manager.cancelUniqueWork(DEFERRED_WORK)
            return
        }

        val minDelay = subscriptions.minOf { maxOf(15L, it.autoUpdateDelay.toLong()) }
        val now = System.currentTimeMillis() / 1000
        val initialDelay = subscriptions.minOf {
            subscriptionDelaySeconds(now, maxOf(it.lastUpdated, it.lastChecked).toLong(), it.autoUpdateDelay)
        }
        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                .build()
        )
    }

    fun onActiveProfilesChanged() {
        if (!collectingSelectionChanges.compareAndSet(false, true)) return
        runOnDefaultDispatcher {
            // One selector change can update both the TCP and UDP paths.
            delay(300)
            collectingSelectionChanges.set(false)
            try {
                val pending = SagerDatabase.groupDao.subscriptions().mapNotNull { it.subscription }
                    .filter {
                        it.autoUpdate && it.updateMode == SubscriptionUpdateMode.DEFER_ACTIVE &&
                            it.deferredProfileIds.isNotEmpty()
                    }
                if (pending.isEmpty()) return@runOnDefaultDispatcher

                val runtime = SubscriptionRuntime.read()
                if (runtime.isTransitioning || pending.none {
                        (!it.updateWhenConnectedOnly || runtime.state.connected) &&
                            it.deferredProfileIds.any { id -> id !in runtime.activeIds }
                    }) return@runOnDefaultDispatcher

                RemoteWorkManager.getInstance(app).enqueueUniqueWork(
                    DEFERRED_WORK,
                    APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<UpdateTask>()
                        .setInputData(workDataOf(DEFERRED_ONLY to true))
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    private fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun reviewTag(groupId: Long) = "subscription-review-$groupId"

    fun clearReviewNotification(groupId: Long) {
        NotificationManagerCompat.from(app).cancel(reviewTag(groupId), REVIEW_NOTIFICATION)
    }

    @SuppressLint("MissingPermission")
    internal fun reportStatus(groupId: Long, reviewWasRequired: Boolean) {
        val group = SagerDatabase.groupDao.getById(groupId) ?: return
        val subscription = group.subscription ?: return
        if (subscription.deferredProfileIds.isNotEmpty()) {
            Logs.i("Subscription $groupId: deferred ${subscription.deferredProfileIds.size} active profiles")
        }
        if (!subscription.reviewRequired) {
            if (reviewWasRequired) clearReviewNotification(groupId)
            return
        }

        Logs.w("Subscription $groupId: ${app.getString(R.string.subscription_review_required)}")
        if (reviewWasRequired || !canNotify(app)) return

        SagerNet.updateNotificationChannels()
        val action = Intent(app, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REVIEW_SUBSCRIPTION)
            .putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, groupId)
        val open = PendingIntent.getActivity(
            app, groupId.hashCode(), action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = app.getString(R.string.subscription_review_notification)
        try {
            NotificationManagerCompat.from(app).notify(
                reviewTag(groupId), REVIEW_NOTIFICATION,
                NotificationCompat.Builder(app, CHANNEL)
                    .setContentTitle(app.getString(R.string.subscription_review_required))
                    .setContentText(group.displayName())
                    .setStyle(NotificationCompat.BigTextStyle().bigText("${group.displayName()}\n$message"))
                    .setSmallIcon(R.drawable.ic_service_active)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        } catch (e: SecurityException) {
            Logs.w(e)
        }
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            val deferredOnly = inputData.getBoolean(DEFERRED_ONLY, false)
            val context = applicationContext
            val manager = NotificationManagerCompat.from(context)
            val progressTag = if (deferredOnly) DEFERRED_WORK else WORK_NAME
            val progress = NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle(context.getString(R.string.subscription_update))
                .setSmallIcon(R.drawable.ic_service_active)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
            var failed = false
            try {
                val now = System.currentTimeMillis() / 1000
                val subscriptions = SagerDatabase.groupDao.subscriptions().filter { group ->
                    val sub = group.subscription!!
                    sub.autoUpdate && if (deferredOnly) {
                        sub.updateMode == SubscriptionUpdateMode.DEFER_ACTIVE && sub.deferredProfileIds.isNotEmpty()
                    } else {
                        subscriptionDelaySeconds(now, maxOf(sub.lastUpdated, sub.lastChecked).toLong(), sub.autoUpdateDelay) == 0L
                    }
                }
                if (subscriptions.isEmpty()) return Result.success()

                val runtime = if (deferredOnly || subscriptions.any { it.subscription!!.updateWhenConnectedOnly }) {
                    SubscriptionRuntime.read()
                } else null
                if (deferredOnly && runtime?.isTransitioning == true) return retryOrFail()
                for (group in subscriptions) {
                    val subscription = group.subscription!!
                    if (subscription.updateWhenConnectedOnly && runtime?.state?.connected != true) continue
                    if (deferredOnly && subscription.deferredProfileIds.all { it in runtime!!.activeIds }) continue

                    Logs.d("work: updating ${group.displayName()}")
                    if (canNotify(context)) {
                        try {
                            manager.notify(progressTag, 2, progress.setContentText(
                                context.getString(R.string.subscription_update_message, group.displayName()),
                            ).build())
                        } catch (e: SecurityException) {
                            Logs.w(e)
                        }
                    }
                    val result = GroupUpdater.executeUpdate(group.id, byUser = false, deferredOnly = deferredOnly)
                    if (result == SubscriptionUpdateResult.RETRY) {
                        failed = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Logs.w(e)
                failed = true
            } finally {
                manager.cancel(progressTag, 2)
            }

            return if (failed) retryOrFail() else Result.success()
        }

        private fun retryOrFail() = if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
