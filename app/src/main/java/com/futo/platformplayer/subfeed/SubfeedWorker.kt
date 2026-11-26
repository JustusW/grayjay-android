package com.futo.platformplayer.subfeed

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the outbox to the server, oldest first. A connection failure or a server error stops the run and lets
 * WorkManager retry with exponential backoff (the work is persisted, so it survives being offline and reboots).
 * A request the server rejects for good (4xx) is dropped and logged rather than blocking everything behind it.
 */
class SubfeedOutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val state = SubfeedStore.state(applicationContext)
        if (!state.enabled || !state.firstSyncDone)
            return@withContext Result.success()
        return@withContext if (drain(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "SubfeedOutbox"
        private const val UNIQUE = "subfeed-outbox"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SubfeedOutboxWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            //APPEND_OR_REPLACE: an op added while a run is busy gets a run after it instead of being missed
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Sends everything it can. False if something is left that should be retried later. */
        fun drain(context: Context): Boolean {
            val outbox = SubfeedStore.outbox(context)
            val state = SubfeedStore.state(context)
            val client = SubfeedClient(state.serverUrl)
            while (true) {
                val op = outbox.takeForSend() ?: return true
                try {
                    val response = send(context, client, op)
                    when {
                        response.ok -> {
                            outbox.remove(op.id)
                            SubfeedStore.update(context) { it.lastSyncAt = System.currentTimeMillis(); it.lastError = null }
                        }
                        response.retryable -> {
                            SubfeedStore.update(context) { it.lastError = "${op.kind}: HTTP ${response.code}" }
                            return false
                        }
                        else -> {
                            Logger.w(TAG, "Server rejected ${op.kind} ${op.path} (HTTP ${response.code}): ${response.body.take(300)}; dropped")
                            outbox.remove(op.id)
                            SubfeedStore.update(context) { it.lastError = "${op.kind} rejected: HTTP ${response.code}" }
                        }
                    }
                } catch (e: IOException) {
                    Logger.i(TAG, "Server not reachable (${e.message}), ${outbox.size()} waiting")
                    SubfeedStore.update(context) { it.lastError = "offline: ${e.message}" }
                    return false
                }
            }
        }

        private fun send(context: Context, client: SubfeedClient, op: OutboxOp): SubfeedResponse {
            if (op.kind != OutboxOp.KIND_QUEUE_REORDER)
                return client.request(op.method, op.path, op.body).also { if (it.ok) rememberVersion(context, it) }

            //A reorder goes against the server's current version and never removes anything there
            val desired = op.body?.jsonObject?.get("items")?.jsonArray?.map { (it as JsonPrimitive).content } ?: listOf()
            var lastResponse: SubfeedResponse? = null
            repeat(2) {
                val current = client.get(SubfeedClient.QUEUE)
                if (!current.ok)
                    return current
                val queue = SubfeedJson.parseQueue(current.json())
                if (queue.importing)
                    return SubfeedResponse(503, "importing")
                val order = WatchLaterDiff.reorderOnto(desired, queue.urls)
                if (order == queue.urls)
                    return SubfeedResponse(200, "unchanged")
                val response = client.put(SubfeedClient.QUEUE, JsonObject(mapOf(
                    "base_version" to JsonPrimitive(queue.version),
                    "items" to JsonArray(order.map { JsonPrimitive(it) })
                )))
                if (response.code != 409) {
                    if (response.ok) rememberVersion(context, response)
                    return response
                }
                lastResponse = response
            }
            return lastResponse!!
        }

        private fun rememberVersion(context: Context, response: SubfeedResponse) {
            val version = SubfeedJson.long(response.json(), "version") ?: return
            SubfeedStore.update(context) { it.queueVersion = version }
        }
    }
}

/**
 * Every 15 minutes while subfeed is on: pull the queue, ask for new priority videos, send the outbox.
 * Started like Grayjay's own BackgroundWorker when the app isn't open; skipped while it is (the app does this itself
 * on start and on every return to the foreground).
 */
class SubfeedPeriodicWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!SubfeedStore.state(appContext).enabled)
            return Result.success()
        if (StateApp.instance.contextOrNull != null) {
            //The app (or another background job) has the state loaded already
            SubfeedBridge.instance.backgroundTick(appContext)
            return Result.success()
        }
        try {
            StateApp.instance.startBackground(appContext, true, false) {
                SubfeedBridge.instance.backgroundTick(appContext)
            }
        } catch (e: Throwable) {
            Logger.e("SubfeedPeriodic", "Background tick failed", e)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "subfeed-periodic"

        fun schedule(context: Context, active: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (active) {
                val request = PeriodicWorkRequestBuilder<SubfeedPeriodicWorker>(15, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
            } else
                wm.cancelUniqueWork(UNIQUE)
        }
    }
}
