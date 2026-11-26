package com.futo.platformplayer.subfeed

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.futo.platformplayer.BuildConfig
import com.futo.platformplayer.Settings
import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.HistoryVideo
import com.futo.platformplayer.models.Subscription
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StateNotifications
import com.futo.platformplayer.states.StatePlaylists
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.stores.FragmentedStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Keeps the subfeed server informed of what happens in Grayjay (subscriptions, watch history, Watch Later) and brings
 * the server's queue order back into Watch Later, without ever risking the local Watch Later:
 *
 *  - Before anything else, the first time: a full local backup file, then the same content as a snapshot on the server.
 *  - First sync: the phone is the truth. Subscriptions, history and Watch Later (in order) go up; nothing comes down
 *    until the server has confirmed the imported Watch Later.
 *  - Then: local changes go out through a persistent outbox; the server order is applied only if it removes at most
 *    three unfinished videos (finished ones may always go), otherwise it's refused and the local list stays.
 *
 * The hooks in Grayjay's states are one line each and call the on* functions here.
 */
class SubfeedBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val watchLaterLock = Object()
    private val historyThrottle = HistoryThrottle<JsonObject>()
    private var _context: Context? = null
    private var _started = false
    private var _diffJob: Job? = null
    private var _historyFlushJob: Job? = null
    private var _lastForegroundSync = 0L

    private val context: Context? get() = _context ?: StateApp.instance.contextOrNull?.applicationContext

    //region Lifecycle
    /** Called once the main app has started (StateApp.mainAppStarted). */
    fun start(context: Context) = guarded("start") {
        _context = context.applicationContext
        if (!_started) {
            _started = true
            StatePlaylists.instance.onWatchLaterChanged.subscribe(this) { scheduleWatchLaterPush() }
        }
        val enabled = refreshConfig()
        SubfeedPeriodicWorker.schedule(context.applicationContext, enabled)
        onForeground()
    }

    /** Called whenever the app comes to the foreground (MainActivity.onResume). */
    fun onForeground() = guarded("foreground") {
        if (!_started || !refreshConfig())
            return@guarded
        val now = System.currentTimeMillis()
        if (now - _lastForegroundSync < 30_000)
            return@guarded
        _lastForegroundSync = now
        scope.launch {
            sync("foreground", interactive = true)
        }
    }

    /** The "sync now" button. */
    fun syncFromUser() {
        val ctx = context ?: return
        if (!refreshConfig()) {
            UIDialogs.toast("subfeed is off")
            return
        }
        SubfeedPeriodicWorker.schedule(ctx, true)
        scope.launch {
            val ok = sync("user", interactive = true)
            UIDialogs.toast(if (ok) "subfeed: synced" else "subfeed: ${statusText(ctx)}")
        }
    }

    /** The periodic background work: like a foreground sync plus asking for new priority videos. */
    suspend fun backgroundTick(context: Context) {
        if (_context == null)
            _context = context.applicationContext
        if (!refreshConfig())
            return
        sync("background", interactive = false)
        try {
            checkPriority(context.applicationContext)
        } catch (e: Throwable) {
            Logger.w(TAG, "Priority check failed: ${e.message}")
        }
    }

    /**
     * Mirrors the settings into the bridge's state file (the outbox worker reads it there) and says whether subfeed is
     * on. False while the settings aren't loaded.
     */
    private fun refreshConfig(): Boolean {
        val ctx = context ?: return false
        if (!FragmentedStorage.isInitialized)
            return SubfeedStore.state(ctx).enabled
        val settings = Settings.instance.subfeed
        val url = settings.serverUrl.ifBlank { SubfeedSettings.DEFAULT_URL }
        val state = SubfeedStore.state(ctx)
        if (state.enabled != settings.enabled || state.serverUrl != url) {
            SubfeedStore.update(ctx) { it.enabled = settings.enabled; it.serverUrl = url }
            SubfeedPeriodicWorker.schedule(ctx, settings.enabled)
        }
        return settings.enabled
    }
    //endregion

    //region Sync
    /** First sync (if not done yet), push local changes, send the outbox, pull the server order. */
    suspend fun sync(reason: String, interactive: Boolean): Boolean = syncMutex.withLock {
        val ctx = context ?: return false
        if (!refreshConfig())
            return false
        try {
            if (!firstSync(ctx))
                return false
            pushWatchLaterChanges()
            historyThrottle.flush(System.currentTimeMillis()).takeIf { it.isNotEmpty() }?.let { enqueueHistory(ctx, it) }
            SubfeedOutboxWorker.drain(ctx)
            postDailySnapshot(ctx)
            pull(ctx, interactive)
            return true
        } catch (e: IOException) {
            Logger.i(TAG, "Sync ($reason): server not reachable (${e.message})")
            SubfeedStore.update(ctx) { it.lastError = "offline: ${e.message}" }
        } catch (e: Throwable) {
            Logger.e(TAG, "Sync ($reason) failed", e)
            SubfeedStore.update(ctx) { it.lastError = "${e.javaClass.simpleName}: ${e.message}" }
        }
        return false
    }

    /**
     * The first sync, resumable step by step: backup file, snapshot, subscriptions, history, Watch Later import,
     * import confirmed. True once all of it is done.
     */
    private fun firstSync(ctx: Context): Boolean {
        var state = SubfeedStore.state(ctx)
        if (state.firstSyncDone)
            return true
        val client = SubfeedClient(state.serverUrl)

        if (state.backupFile == null) {
            //Protection first: nothing is sent, and nothing will ever be applied, before this file exists
            val file = writeBackup(ctx, "first")
            state = SubfeedStore.update(ctx) { it.backupFile = file.absolutePath }
            Logger.i(TAG, "First sync: local backup written to ${file.absolutePath}")
        }
        if (!state.snapshotPosted) {
            val snapshot = Json.parseToJsonElement(File(state.backupFile!!).readText())
            expectOk(client.post(SubfeedClient.SNAPSHOT, snapshot), "snapshot")
            state = SubfeedStore.update(ctx) { it.snapshotPosted = true; it.lastSnapshotAt = System.currentTimeMillis() }
        }
        if (!state.subscriptionsPushed) {
            expectOk(client.post(SubfeedClient.SUBSCRIPTIONS, fullSubscriptions()), "subscriptions")
            state = SubfeedStore.update(ctx) { it.subscriptionsPushed = true }
        }
        if (!state.historyPushed) {
            val history = allHistory()
            for (chunk in history.chunked(500))
                expectOk(client.post(SubfeedClient.HISTORY, JsonObject(mapOf("items" to JsonArray(chunk.map { SubfeedJson.history(it) })))), "history")
            state = SubfeedStore.update(ctx) { it.historyPushed = true }
        }
        if (!state.importPosted) {
            val urls = localWatchLater()
            //In parts (the server takes at most 5000 per request); offset makes a repeated part replace itself
            for ((offset, part) in QueueImportPlan.parts(urls)) {
                val final = offset + part.size == urls.size
                val response = client.post(SubfeedClient.QUEUE_IMPORT, JsonObject(mapOf(
                    "items" to JsonArray(part.map { JsonPrimitive(it) }),
                    "final" to JsonPrimitive(final),
                    "offset" to JsonPrimitive(offset)
                )))
                if (response.code == 409 && !SubfeedJson.parseQueue(response.json()).importing && SubfeedJson.parseQueue(response.json()).version != null) {
                    //Imported already: our earlier import got through but its answer was lost, or this phone was set up
                    //again. Never import over it; the queue is compared below and then goes through the bulk guard.
                    Logger.w(TAG, "First sync: the server's queue was imported already")
                    break
                }
                expectOk(response, "queue import")
            }
            state = SubfeedStore.update(ctx) { it.importPosted = true; it.importedUrls = urls }
            Logger.i(TAG, "First sync: Watch Later (${urls.size}) imported")
        }
        if (!state.importConfirmed) {
            val response = client.get(SubfeedClient.QUEUE)
            expectOk(response, "queue")
            val queue = SubfeedJson.parseQueue(response.json())
            if (queue.importing) {
                //Our import didn't end it (a part got lost): send it again
                SubfeedStore.update(ctx) { it.importPosted = false; it.lastError = "import not finished on the server, sending again" }
                return false
            }
            if (queue.urls != state.importedUrls) {
                //It can't be imported again (409 "already imported"). What the phone sent is the base the server's
                //differences are judged against: the pull's bulk guard decides, the local list stays protected.
                Logger.w(TAG, "First sync: server queue (${queue.urls.size}) differs from the import (${state.importedUrls.size}); the bulk guard decides")
            }
            SubfeedStore.update(ctx) {
                it.importConfirmed = true
                it.syncedQueue = it.importedUrls
                it.queueVersion = queue.version
                it.prioritySince = SubfeedJson.isoNow()
                it.lastSyncAt = System.currentTimeMillis()
                it.lastError = null
            }
            Logger.i(TAG, "First sync: done, import confirmed (version ${queue.version})")
        }
        return true
    }

    private fun expectOk(response: SubfeedResponse, what: String) {
        if (!response.ok)
            throw IllegalStateException("$what: HTTP ${response.code} ${response.body.take(200)}")
    }

    /** Pulls the server's queue and applies it to Watch Later if the bulk guard allows it. */
    private suspend fun pull(ctx: Context, interactive: Boolean) {
        val outbox = SubfeedStore.outbox(ctx)
        if (outbox.hasQueueOps()) {
            //Local Watch Later changes haven't reached the server yet; its order is behind ours
            Logger.i(TAG, "Pull skipped: ${outbox.size()} changes still waiting to be sent")
            return
        }
        val state = SubfeedStore.state(ctx)
        val response = SubfeedClient(state.serverUrl).get(SubfeedClient.QUEUE)
        expectOk(response, "queue")
        val queue = SubfeedJson.parseQueue(response.json())
        if (queue.importing)
            return
        applyServerQueue(ctx, queue, interactive)
    }

    /** Watch Later in order, each URL in the server's form (SubfeedUrls): what is compared with the server's queue. */
    private fun localWatchLater(): List<String> = StatePlaylists.instance.getWatchLater().map { SubfeedUrls.normalize(it.url) }

    /** Exposed for the restore and the tests: applies [queue] through the bulk guard. */
    suspend fun applyServerQueue(ctx: Context, queue: QueueView, interactive: Boolean): QueueDecision {
        val localVideos = StatePlaylists.instance.getWatchLater()
        val local = localVideos.map { SubfeedUrls.normalize(it.url) }
        if (local != SubfeedStore.state(ctx).syncedQueue) {
            //Watch Later changed since the server was last told: those changes go out first, the server's order is
            //only looked at once it has them (otherwise a fresh local removal would look like a server addition)
            Logger.i(TAG, "Pull skipped: local Watch Later changes not sent yet")
            pushWatchLaterChanges()
            SubfeedOutboxWorker.enqueue(ctx)
            return QueueDecision.NoChange
        }
        val byUrl = localVideos.associateBy { SubfeedUrls.normalize(it.url) }
        val decision = QueueGuard.decide(local, queue.urls, { url -> byUrl[url]?.let { isFinished(it.url, it.duration) } ?: false })
        when (decision) {
            is QueueDecision.NoChange -> SubfeedStore.update(ctx) { it.syncedQueue = local; it.queueVersion = queue.version }
            is QueueDecision.Refuse -> {
                val message = "subfeed: refused a bulk change to Watch Later"
                Logger.w(TAG, "$message (server version ${queue.version} would remove ${decision.unfinishedRemovals.size} unfinished videos: ${decision.unfinishedRemovals.take(10)})")
                val alreadyShown = SubfeedStore.state(ctx).refusedVersion == queue.version && queue.version != null
                SubfeedStore.update(ctx) {
                    it.lastRefusal = "${SubfeedJson.isoNow()}: server would remove ${decision.unfinishedRemovals.size} unfinished"
                    it.refusedVersion = queue.version
                }
                if (interactive && !alreadyShown)
                    UIDialogs.toast(message)
            }
            is QueueDecision.Apply -> {
                writeBackup(ctx, "before-pull")
                val serverItems = queue.items.associateBy { SubfeedJson.str(it, "url") }
                val updated = decision.order.mapNotNull { url -> byUrl[url] ?: serverItems[url]?.let { SubfeedJson.toVideo(it) } }
                val applied = withContext(Dispatchers.Main) {
                    synchronized(watchLaterLock) {
                        if (localWatchLater() != local)
                            return@synchronized false //changed locally meanwhile; next pull
                        StatePlaylists.instance.updateWatchLater(ArrayList(updated), false)
                        SubfeedStore.update(ctx) { it.syncedQueue = updated.map { v -> SubfeedUrls.normalize(v.url) }; it.queueVersion = queue.version; it.lastSyncAt = System.currentTimeMillis() }
                        true
                    }
                }
                if (!applied)
                    return QueueDecision.NoChange
                Logger.i(TAG, "Pull applied: ${decision.removed.size} removed, ${decision.added.size} added, ${updated.size} in Watch Later")
            }
        }
        return decision
    }

    private fun isFinished(url: String, durationS: Long): Boolean {
        if (durationS <= 0)
            return false
        return StateHistory.instance.getHistoryPosition(url) >= durationS * 0.9
    }

    private fun postDailySnapshot(ctx: Context) {
        val state = SubfeedStore.state(ctx)
        if (System.currentTimeMillis() - state.lastSnapshotAt < 24 * 3600 * 1000L)
            return
        val response = SubfeedClient(state.serverUrl).post(SubfeedClient.SNAPSHOT, buildSnapshot("daily"))
        if (response.ok)
            SubfeedStore.update(ctx) { it.lastSnapshotAt = System.currentTimeMillis() }
    }
    //endregion

    //region Local changes (hooks)
    private fun eventsAllowed(ctx: Context): Boolean {
        refreshConfig()
        val state = SubfeedStore.state(ctx)
        //Before the first backup exists nothing at all is queued; the first sync sends the full state anyway
        return state.enabled && state.backupFile != null
    }

    /** The hooks run inside Grayjay's own code paths: whatever goes wrong here must never break those. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook ($what) failed", e)
        }
    }

    private fun scheduleWatchLaterPush() {
        if (!SubfeedStore.state(context ?: return).firstSyncDone)
            return
        _diffJob?.cancel()
        _diffJob = scope.launch {
            delay(1500)
            pushWatchLaterChanges()
            context?.let { SubfeedOutboxWorker.enqueue(it) }
        }
    }

    /** Turns the difference between the server's last known queue and the local Watch Later into outbox ops. */
    fun pushWatchLaterChanges() {
        val ctx = context ?: return
        //Watch Later saves additions asynchronously while its order is set at once: a list read in between lacks
        //the new videos, which would go out as removals. Wait (briefly) until both agree.
        for (attempt in 0 until 40) {
            val items = StatePlaylists.instance.getWatchLater().map { it.url }.toHashSet()
            if (items == StatePlaylists.instance.getWatchLaterOrdering().toHashSet())
                break
            Thread.sleep(250)
        }
        synchronized(watchLaterLock) {
            val state = SubfeedStore.state(ctx)
            if (!state.enabled || !state.firstSyncDone)
                return
            val local = localWatchLater()
            val changes = WatchLaterDiff.changes(state.syncedQueue, local)
            if (changes.isEmpty())
                return
            val outbox = SubfeedStore.outbox(ctx)
            for (change in changes) {
                when (change) {
                    is QueueChange.Add -> outbox.add(OutboxOp.KIND_QUEUE_ADD, "POST", SubfeedClient.QUEUE_ADD, buildJsonObject {
                        put("url", change.url); put("position", if (change.top) "top" else "bottom")
                    })
                    is QueueChange.Remove -> outbox.add(OutboxOp.KIND_QUEUE_REMOVE, "POST", SubfeedClient.QUEUE_REMOVE, buildJsonObject {
                        put("url", change.url)
                    })
                    is QueueChange.Reorder -> outbox.add(OutboxOp.KIND_QUEUE_REORDER, "PUT", SubfeedClient.QUEUE,
                        JsonObject(mapOf("items" to JsonArray(change.order.map { JsonPrimitive(it) }))))
                }
            }
            SubfeedStore.update(ctx) { it.syncedQueue = local }
            Logger.i(TAG, "Watch Later changed: ${changes.size} changes queued for the server")
        }
    }

    fun onSubscriptionAdded(sub: Subscription) = guarded("subscription added") {
        val ctx = context ?: return@guarded
        if (!eventsAllowed(ctx)) return@guarded
        enqueue(ctx, OutboxOp.KIND_SUBSCRIPTIONS, "POST", SubfeedClient.SUBSCRIPTIONS, JsonObject(mapOf(
            "full" to JsonPrimitive(false),
            "items" to JsonArray(listOf(SubfeedJson.subscription(sub))),
            "removed" to JsonArray(listOf())
        )))
    }

    fun onSubscriptionRemoved(url: String, at: OffsetDateTime = OffsetDateTime.now()) = guarded("subscription removed") {
        val ctx = context ?: return@guarded
        if (!eventsAllowed(ctx)) return@guarded
        enqueue(ctx, OutboxOp.KIND_SUBSCRIPTIONS, "POST", SubfeedClient.SUBSCRIPTIONS, JsonObject(mapOf(
            "full" to JsonPrimitive(false),
            "items" to JsonArray(listOf()),
            "removed" to JsonArray(listOf(SubfeedJson.removal(url, at)))
        )))
    }

    /** A history position changed (StateHistory.updateHistoryPosition). Throttled to one per video every 30 s. */
    fun onHistoryPosition(history: HistoryVideo, isUserAction: Boolean) = guarded("history") {
        val ctx = context ?: return@guarded
        val duration = history.video.duration
        //Positions arriving from other Grayjay devices or imports aren't watching on this phone - except "watched"
        if (!isUserAction && !(duration > 0 && history.position >= duration - 1))
            return@guarded
        if (!eventsAllowed(ctx)) return@guarded
        val now = System.currentTimeMillis()
        val toSend = historyThrottle.offer(history.video.url, SubfeedJson.history(history), history.position, duration, now)
        if (toSend.isNotEmpty())
            enqueueHistory(ctx, toSend)
        scheduleHistoryFlush()
    }

    /** Playback paused or ended: send the held-back positions now. */
    fun onPlaybackPaused() = guarded("pause") {
        val ctx = context ?: return@guarded
        val toSend = historyThrottle.flush(System.currentTimeMillis())
        if (toSend.isNotEmpty())
            enqueueHistory(ctx, toSend)
    }

    private fun scheduleHistoryFlush() {
        if (_historyFlushJob?.isActive == true)
            return
        val ctx = context ?: return
        _historyFlushJob = scope.launch {
            while (true) {
                val wait = historyThrottle.nextDueInMs(System.currentTimeMillis()) ?: break
                delay(wait + 50)
                val due = historyThrottle.due(System.currentTimeMillis())
                if (due.isNotEmpty())
                    enqueueHistory(ctx, due)
            }
        }
    }

    private fun enqueueHistory(ctx: Context, items: List<JsonObject>) {
        enqueue(ctx, OutboxOp.KIND_HISTORY, "POST", SubfeedClient.HISTORY, JsonObject(mapOf("items" to JsonArray(items))))
    }

    private fun enqueue(ctx: Context, kind: String, method: String, path: String, body: JsonElement) {
        try {
            SubfeedStore.outbox(ctx).add(kind, method, path, body)
            if (SubfeedStore.state(ctx).firstSyncDone)
                SubfeedOutboxWorker.enqueue(ctx)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to queue $kind", e)
        }
    }
    //endregion

    //region Snapshot, backup, restore
    private fun fullSubscriptions(): JsonObject = JsonObject(mapOf(
        "full" to JsonPrimitive(true),
        "items" to JsonArray(StateSubscriptions.instance.getSubscriptions().map { SubfeedJson.subscription(it) }),
        "removed" to JsonArray(StateSubscriptions.instance.getSubscriptionRemovals().map { (url, at) ->
            SubfeedJson.removal(url, OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(at), ZoneOffset.UTC))
        })
    ))

    private fun allHistory(): List<HistoryVideo> = StateHistory.instance.getRecentHistory(OffsetDateTime.MIN, Int.MAX_VALUE)

    /** Watch Later in order, the whole history, the subscriptions: the backup file and server snapshot content. */
    fun buildSnapshot(reason: String): JsonObject {
        val watchLater = StatePlaylists.instance.getWatchLater()
        val now = SubfeedJson.isoNow()
        return JsonObject(mapOf(
            "created" to JsonPrimitive(now),
            "taken_at" to JsonPrimitive(now),
            "reason" to JsonPrimitive(reason),
            "device" to JsonPrimitive(Build.MODEL),
            "app_version" to JsonPrimitive(BuildConfig.VERSION_NAME),
            "watch_later" to JsonArray(watchLater.map { SubfeedJson.video(it, StatePlaylists.instance.getWatchLaterAddTime(it.url)) }),
            "history" to JsonArray(allHistory().map { SubfeedJson.history(it) }),
            "subscriptions" to JsonArray(StateSubscriptions.instance.getSubscriptions().map { SubfeedJson.subscription(it) })
        ))
    }

    fun backupDir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: File(SubfeedStore.dir(ctx), "backups").also { it.mkdirs() }

    /**
     * Writes the current state to Android/data/<package>/files/subfeed-backup-<timestamp>.json and reads it back to
     * make sure it's complete. Throws if it can't, so whatever needed the backup doesn't happen.
     */
    fun writeBackup(ctx: Context, reason: String): File {
        val snapshot = buildSnapshot(reason)
        val dir = backupDir(ctx)
        dir.mkdirs()
        val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val file = File(dir, "subfeed-backup-$stamp.json")
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(JsonElement.serializer(), snapshot))
        if (!tmp.renameTo(file))
            throw IOException("Could not write ${file.absolutePath}")
        val check = Json.parseToJsonElement(file.readText()) as JsonObject
        val expected = (snapshot["watch_later"] as JsonArray).size
        if ((check["watch_later"] as? JsonArray)?.size != expected)
            throw IOException("Backup ${file.absolutePath} did not read back complete")
        pruneBackups(ctx, dir)
        return file
    }

    /** Keeps the very first backup and the newest ten others. */
    private fun pruneBackups(ctx: Context, dir: File) {
        val first = SubfeedStore.state(ctx).backupFile
        val others = listBackups(dir).filter { it.absolutePath != first }
        for (old in others.drop(10))
            old.delete()
    }

    /** Backup files, newest first. */
    fun listBackups(dir: File): List<File> =
        (dir.listFiles { f -> f.name.startsWith("subfeed-backup-") && f.name.endsWith(".json") } ?: arrayOf())
            .sortedByDescending { it.name }

    /** The Watch Later of a snapshot/backup, in order. */
    fun watchLaterOf(snapshot: JsonObject): List<SerializedPlatformVideo> =
        (snapshot["watch_later"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { item -> SubfeedJson.toVideo(item) } } ?: listOf()

    /**
     * The server's snapshots that hold a Watch Later, oldest first (GET /sync/snapshots: id, at, counts), and one of
     * them in full (GET /sync/snapshots/{id}: the phone's JSON is its "data").
     */
    fun listSnapshots(ctx: Context): List<JsonObject> {
        val response = SubfeedClient(SubfeedStore.state(ctx).serverUrl).get(SubfeedClient.SNAPSHOTS)
        expectOk(response, "snapshots")
        return SubfeedJson.items(response.json(), "snapshots")
            .filter { SubfeedJson.long(it["counts"], "watch_later") != null }
            .sortedBy { SubfeedJson.long(it, "id") ?: 0 }
    }

    fun fetchSnapshot(ctx: Context, id: Long): JsonObject? {
        val response = SubfeedClient(SubfeedStore.state(ctx).serverUrl).get(SubfeedClient.SNAPSHOTS + "/" + id)
        expectOk(response, "snapshot $id")
        return (response.json() as? JsonObject)?.get("data") as? JsonObject
    }

    /** Replaces Watch Later with [videos] after writing a backup of the current state. */
    suspend fun restoreWatchLater(ctx: Context, videos: List<SerializedPlatformVideo>, source: String) {
        writeBackup(ctx, "before-restore")
        withContext(Dispatchers.Main) {
            StatePlaylists.instance.updateWatchLater(ArrayList(videos), true)
        }
        Logger.i(TAG, "Watch Later restored from $source (${videos.size})")
        //The server learns about it through the usual change push (single adds/removes, then the order)
        pushWatchLaterChanges()
        SubfeedOutboxWorker.enqueue(ctx)
    }

    fun restoreFromServerWithConfirmation() {
        val activity = StateApp.instance.activity ?: return
        val ctx = activity.applicationContext
        fun describe(s: JsonObject) = "#${SubfeedJson.long(s, "id")} of ${SubfeedJson.str(s, "at") ?: "?"}, ${SubfeedJson.long(s["counts"], "watch_later")} videos"
        fun restore(s: JsonObject) = scope.launch {
            try {
                val snapshot = fetchSnapshot(ctx, SubfeedJson.long(s, "id") ?: return@launch)
                if (snapshot == null) {
                    UIDialogs.toast("subfeed: snapshot not available")
                    return@launch
                }
                confirmRestore(activity, ctx, watchLaterOf(snapshot), "server snapshot ${describe(s)}")
            } catch (e: Throwable) {
                Logger.e(TAG, "Fetching the snapshot failed", e)
                UIDialogs.toast("subfeed: snapshot not available (${e.message})")
            }
        }
        scope.launch {
            try {
                val snapshots = listSnapshots(ctx)
                if (snapshots.isEmpty()) {
                    UIDialogs.toast("subfeed: no snapshot on the server")
                    return@launch
                }
                val newest = snapshots.last()
                val first = snapshots.first()
                withContext(Dispatchers.Main) {
                    //As the local restore: the newest, or the very first (Watch Later before subfeed changed anything)
                    val actions = mutableListOf(UIDialogs.Action("Cancel", {}), UIDialogs.Action("Newest", { restore(newest) }, UIDialogs.ActionStyle.PRIMARY))
                    if (first !== newest)
                        actions.add(1, UIDialogs.Action("First", { restore(first) }, UIDialogs.ActionStyle.ACCENT))
                    UIDialogs.showDialog(activity, com.futo.platformplayer.R.drawable.ic_move_up, "Restore Watch Later from a server snapshot?",
                        "Newest: ${describe(newest)}" + (if (first !== newest) "\nFirst: ${describe(first)}" else ""), null, 0, *actions.toTypedArray())
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "Listing the snapshots failed", e)
                UIDialogs.toast("subfeed: snapshots not available (${e.message})")
            }
        }
    }

    fun restoreFromLocalWithConfirmation() {
        val activity = StateApp.instance.activity ?: return
        val ctx = activity.applicationContext
        val backups = listBackups(backupDir(ctx))
        val first = SubfeedStore.state(ctx).backupFile?.let { File(it) }?.takeIf { it.exists() }
        if (backups.isEmpty()) {
            UIDialogs.toast("subfeed: no local backup in ${backupDir(ctx).absolutePath}")
            return
        }
        fun restoreFile(file: File) = scope.launch {
            try {
                val snapshot = Json.parseToJsonElement(file.readText()) as JsonObject
                confirmRestore(activity, ctx, watchLaterOf(snapshot), "local backup ${file.name}")
            } catch (e: Throwable) {
                Logger.e(TAG, "Reading ${file.absolutePath} failed", e)
                UIDialogs.toast("subfeed: can't read ${file.name}")
            }
        }
        val actions = mutableListOf(UIDialogs.Action("Cancel", {}), UIDialogs.Action("Newest", { restoreFile(backups.first()) }, UIDialogs.ActionStyle.PRIMARY))
        if (first != null && first.absolutePath != backups.first().absolutePath)
            actions.add(1, UIDialogs.Action("First (before subfeed)", { restoreFile(first) }, UIDialogs.ActionStyle.ACCENT))
        UIDialogs.showDialog(activity, com.futo.platformplayer.R.drawable.ic_move_up, "Restore Watch Later from a local backup?",
            "Newest: ${backups.first().name}" + (if (first != null) "\nFirst: ${first.name}" else ""), null, 0, *actions.toTypedArray())
    }

    private suspend fun confirmRestore(activity: Context, ctx: Context, videos: List<SerializedPlatformVideo>, source: String) {
        val current = StatePlaylists.instance.getWatchLater().size
        withContext(Dispatchers.Main) {
            UIDialogs.showDialog(activity, com.futo.platformplayer.R.drawable.ic_move_up, "Restore Watch Later?",
                "Replace the current Watch Later ($current videos) with the $source (${videos.size} videos). A backup of the current state is written first.",
                null, 0,
                UIDialogs.Action("Cancel", {}),
                UIDialogs.Action("Restore", {
                    scope.launch {
                        try {
                            restoreWatchLater(ctx, videos, source)
                            UIDialogs.toast("subfeed: Watch Later restored (${videos.size})")
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Restore failed", e)
                            UIDialogs.toast("subfeed: restore failed (${e.message})")
                        }
                    }
                }, UIDialogs.ActionStyle.PRIMARY))
        }
    }
    //endregion

    //region Priority
    private fun checkPriority(ctx: Context) {
        val state = SubfeedStore.state(ctx)
        if (!state.firstSyncDone)
            return
        val since = state.prioritySince ?: SubfeedJson.isoNow()
        val response = SubfeedClient(state.serverUrl).get(SubfeedClient.PRIORITY_NEW + "?since=" + URLEncoder.encode(since, "UTF-8"))
        expectOk(response, "priority")
        val json = response.json()
        val items = SubfeedJson.items(json, "videos")
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = StateNotifications.instance.contentNotifChannel
        manager.createNotificationChannel(channel)
        for (item in items.take(5)) {
            val video = SubfeedJson.toVideo(item) ?: continue
            StateNotifications.instance.notifyNewContentWithThumbnail(ctx, manager, channel, 7000 + (video.url.hashCode() and 0xFFF), video)
        }
        //The server's own time of the newest one: the phone's clock never decides what counts as new
        val next = PriorityCursor.next(since, items.map { SubfeedJson.str(it, "found_at") })
        SubfeedStore.update(ctx) { it.prioritySince = next }
        if (items.isNotEmpty())
            Logger.i(TAG, "Priority: ${items.size} new")
    }
    //endregion

    fun statusText(ctx: Context): String {
        val state = SubfeedStore.state(ctx)
        val base = when {
            !state.enabled -> "off"
            state.backupFile == null -> "waiting for the first sync"
            !state.snapshotPosted -> "first sync: sending the snapshot"
            !state.subscriptionsPushed -> "first sync: sending subscriptions"
            !state.historyPushed -> "first sync: sending history"
            !state.importConfirmed -> "first sync: Watch Later import not confirmed yet"
            else -> "in sync"
        }
        return base + (state.lastError?.let { " - $it" } ?: "") + (state.lastRefusal?.let { "\nlast refusal: $it" } ?: "")
    }

    companion object {
        private const val TAG = "Subfeed"
        private var _instance: SubfeedBridge? = null
        val instance: SubfeedBridge
            @Synchronized get() = _instance ?: SubfeedBridge().also { _instance = it }
    }
}
