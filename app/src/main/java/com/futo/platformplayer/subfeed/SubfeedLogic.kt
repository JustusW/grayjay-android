package com.futo.platformplayer.subfeed

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/*
 * The parts of the subfeed bridge that decide things, free of Android so they can be unit tested:
 * the Watch Later bulk guard, the local-change diff, the history throttle and the persistent outbox.
 */

/** What a pull from the server may do to the local Watch Later. */
sealed class QueueDecision {
    /** Server and phone agree (same urls in the same order). */
    object NoChange : QueueDecision()

    /** Apply [order]: [removed] urls leave Watch Later (all of them finished or at most the allowed few), [added] are new. */
    data class Apply(val order: List<String>, val removed: List<String>, val added: List<String>) : QueueDecision()

    /** The server would remove [unfinishedRemovals] unfinished videos at once: keep the local list as it is. */
    data class Refuse(val unfinishedRemovals: List<String>) : QueueDecision()
}

object QueueGuard {
    const val MAX_REMOVALS = 3

    /**
     * Decides whether the server order [server] may replace the local Watch Later [local].
     * Removing a video the phone has watched to the end ([isFinished], >= 90 %) is always allowed - those leave one by one
     * as they're watched. Removing more than [maxRemovals] unfinished videos in one pull is refused outright, order included.
     */
    fun decide(local: List<String>, server: List<String>, isFinished: (String) -> Boolean, maxRemovals: Int = MAX_REMOVALS): QueueDecision {
        val serverOrder = server.distinct()
        if (serverOrder == local)
            return QueueDecision.NoChange
        val serverSet = serverOrder.toHashSet()
        val localSet = local.toHashSet()
        val removed = local.filter { it !in serverSet }
        val unfinished = removed.filter { !isFinished(it) }
        if (unfinished.size > maxRemovals)
            return QueueDecision.Refuse(unfinished)
        return QueueDecision.Apply(serverOrder, removed, serverOrder.filter { it !in localSet })
    }
}

/** One change to push to the server's queue, derived from a local Watch Later change. */
sealed class QueueChange {
    /** The server adds at the top or the bottom only (POST /queue/add position "top" | "bottom"). */
    data class Add(val url: String, val top: Boolean) : QueueChange()
    data class Remove(val url: String) : QueueChange()
    data class Reorder(val order: List<String>) : QueueChange()
}

object WatchLaterDiff {
    /**
     * The changes that turn the queue the server was last told about ([base]) into the local Watch Later ([local]):
     * single removals and additions (never a bulk replace, which the server refuses), then a reorder if the order
     * still differs after those.
     */
    fun changes(base: List<String>, local: List<String>): List<QueueChange> {
        if (base == local)
            return listOf()
        val localSet = local.toHashSet()
        val result = mutableListOf<QueueChange>()
        val working = base.distinct().toMutableList()
        for (url in working.toList()) {
            if (url !in localSet) {
                result.add(QueueChange.Remove(url))
                working.remove(url)
            }
        }
        val workingSet = working.toHashSet()
        for ((index, url) in local.withIndex()) {
            if (url !in workingSet) {
                //Only the top or the bottom: anything in between is put right by the reorder below
                val top = index == 0 && working.isNotEmpty()
                result.add(QueueChange.Add(url, top))
                if (top) working.add(0, url) else working.add(url)
                workingSet.add(url)
            }
        }
        if (working != local)
            result.add(QueueChange.Reorder(local))
        return result
    }

    /**
     * The order to PUT for a reorder: [desired] as far as the server has those videos, followed by whatever the server
     * holds that [desired] doesn't (added elsewhere meanwhile), so a reorder never removes anything on the server.
     */
    fun reorderOnto(desired: List<String>, server: List<String>): List<String> {
        val serverSet = server.toHashSet()
        val desiredSet = desired.toHashSet()
        return desired.filter { it in serverSet } + server.filter { it !in desiredSet }
    }
}

/**
 * URLs the way the server keeps them (subfeed's urls.normalize_video_url): a YouTube video URL (watch?v=, youtu.be/,
 * /shorts/, /live/, /embed/, /v/) becomes https://www.youtube.com/watch?v=<id>, anything else stays as it is. The phone
 * compares its Watch Later with the server's queue in this form, or a youtu.be entry would never match.
 */
object SubfeedUrls {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val URL = Regex("^(?:([A-Za-z][A-Za-z0-9+.-]*):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#.*)?$")

    private class Parts(val scheme: String, val host: String, val path: String, val query: String)

    private fun split(s: String): Parts? {
        val m = URL.matchEntire(s) ?: return null
        val netloc = m.groupValues[2]
        val host = netloc.substringAfterLast('@').let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
        return Parts(m.groupValues[1].lowercase(), host.lowercase(), m.groupValues[3], m.groupValues[4])
    }

    fun videoId(url: String): String? {
        var s = url.trim()
        if (VIDEO_ID.matches(s))
            return s
        if (s.startsWith("youtu.be/"))
            s = "https://$s"
        val u = split(s) ?: return null
        val parts = u.path.split('/').filter { it.isNotEmpty() }
        if (u.scheme in setOf("http", "https") && u.host == "youtu.be" && parts.isNotEmpty() && VIDEO_ID.matches(parts[0]))
            return parts[0]
        val prefixed = if (s.startsWith("youtube.com/") || s.startsWith("www.youtube.com/") || s.startsWith("m.youtube.com/")) "https://$s" else s
        val u2 = split(prefixed) ?: return null
        if (u2.scheme !in setOf("http", "https") || u2.host !in HOSTS)
            return null
        if (parts.firstOrNull() == "watch") {
            val v = u2.query.split('&').firstOrNull { it.substringBefore('=') == "v" }?.substringAfter('=', "") ?: ""
            return if (VIDEO_ID.matches(v)) v else null
        }
        if (parts.size >= 2 && parts[0] in setOf("shorts", "live", "embed", "v") && VIDEO_ID.matches(parts[1]))
            return parts[1]
        return null
    }

    fun normalize(url: String): String = videoId(url)?.let { "https://www.youtube.com/watch?v=$it" } ?: url.trim()
}

/** POST /queue/import in parts: (offset, urls) each, the last one final. Always at least one part (an empty list too). */
object QueueImportPlan {
    const val PART_SIZE = 1000

    fun parts(urls: List<String>, size: Int = PART_SIZE): List<Pair<Int, List<String>>> =
        if (urls.isEmpty()) listOf(0 to listOf()) else urls.chunked(size).mapIndexed { i, chunk -> i * size to chunk }
}

/** Where the next GET /priority/new starts: the newest found_at seen (by instant, offsets may differ), else unchanged. */
object PriorityCursor {
    fun next(previous: String?, foundAt: List<String?>): String? {
        val newest = foundAt.filterNotNull().mapNotNull { at -> SubfeedJson.parseTime(at)?.let { at to it } }
            .maxByOrNull { it.second.toInstant() } ?: return previous
        val prev = previous?.let { SubfeedJson.parseTime(it) }
        return if (prev == null || newest.second.toInstant().isAfter(prev.toInstant())) newest.first else previous
    }
}

/**
 * Throttles history position updates: at most one per video every [intervalMs], except the update that first crosses
 * 90 % (finished) and explicit flushes (pause, end). Updates held back are kept as pending and come out through [due].
 */
class HistoryThrottle<T>(private val intervalMs: Long = 30_000) {
    private val lastSentAt = HashMap<String, Long>()
    private val lastSentFinished = HashMap<String, Boolean>()
    private val pending = LinkedHashMap<String, T>()

    @Synchronized
    fun offer(url: String, item: T, positionS: Long, durationS: Long, nowMs: Long): List<T> {
        val finished = durationS > 0 && positionS >= durationS * 0.9
        val last = lastSentAt[url]
        val crossedFinish = finished && lastSentFinished[url] != true
        if (last == null || nowMs - last >= intervalMs || crossedFinish) {
            pending.remove(url)
            lastSentAt[url] = nowMs
            lastSentFinished[url] = finished
            return listOf(item)
        }
        pending[url] = item
        return listOf()
    }

    /** Pending updates whose interval has passed. */
    @Synchronized
    fun due(nowMs: Long): List<T> {
        val result = mutableListOf<T>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (url, item) = it.next()
            if (nowMs - (lastSentAt[url] ?: 0) >= intervalMs) {
                result.add(item)
                lastSentAt[url] = nowMs
                it.remove()
            }
        }
        return result
    }

    /** Every pending update, now (pause / end of playback). */
    @Synchronized
    fun flush(nowMs: Long): List<T> {
        val result = pending.values.toList()
        for (url in pending.keys)
            lastSentAt[url] = nowMs
        pending.clear()
        return result
    }

    /** Milliseconds until the next pending update is due, or null if nothing is pending. */
    @Synchronized
    fun nextDueInMs(nowMs: Long): Long? = pending.keys.minOfOrNull { ((lastSentAt[it] ?: 0) + intervalMs - nowMs).coerceAtLeast(0) }

    @Synchronized
    fun pendingCount(): Int = pending.size
}

/** One request waiting to reach the server. [kind] is one of the KIND_ constants. */
@Serializable
data class OutboxOp(
    val id: Long,
    val kind: String,
    val method: String,
    val path: String,
    val body: JsonElement? = null,
    val created: Long = 0,
    val attempts: Int = 0
) {
    companion object {
        const val KIND_HISTORY = "history"
        const val KIND_SUBSCRIPTIONS = "subscriptions"
        const val KIND_QUEUE_ADD = "queue_add"
        const val KIND_QUEUE_REMOVE = "queue_remove"
        const val KIND_QUEUE_REORDER = "queue_reorder"
        const val KIND_SNAPSHOT = "snapshot"

        val QUEUE_KINDS = setOf(KIND_QUEUE_ADD, KIND_QUEUE_REMOVE, KIND_QUEUE_REORDER)
    }
}

/**
 * A FIFO of requests kept in a file, so nothing is lost while offline or across a reboot. Every change is written
 * through (temp file + rename). Consecutive history batches are merged (newest position per video wins) so a long
 * offline stretch doesn't grow it without bound.
 */
class Outbox(private val file: File, private val maxHistoryBatch: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ops = mutableListOf<OutboxOp>()
    private var nextId = 1L

    init {
        load()
    }

    @Synchronized
    private fun load() {
        ops.clear()
        if (file.exists()) {
            try {
                ops.addAll(json.decodeFromString<List<OutboxOp>>(file.readText()))
            } catch (e: Throwable) {
                //A damaged outbox must not stop the app; keep the bad file next to it for inspection
                file.copyTo(File(file.parentFile, file.name + ".damaged-" + System.currentTimeMillis()), true)
            }
        }
        nextId = (ops.maxOfOrNull { it.id } ?: 0) + 1
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ops.toList()))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun add(kind: String, method: String, path: String, body: JsonElement?, nowMs: Long = System.currentTimeMillis()): OutboxOp {
        val last = ops.lastOrNull()
        if (kind == OutboxOp.KIND_HISTORY && last != null && last.kind == OutboxOp.KIND_HISTORY && last.attempts == 0 && body is JsonObject) {
            val merged = mergeHistory(last.body as? JsonObject, body)
            if (merged != null) {
                ops[ops.size - 1] = last.copy(body = merged)
                persist()
                return ops.last()
            }
        }
        val op = OutboxOp(nextId++, kind, method, path, body, nowMs)
        ops.add(op)
        persist()
        return op
    }

    private fun mergeHistory(a: JsonObject?, b: JsonObject): JsonObject? {
        val aItems = a?.get("items")?.jsonArray ?: return null
        val bItems = b["items"]?.jsonArray ?: return null
        val byUrl = LinkedHashMap<String, JsonElement>()
        for (item in aItems + bItems) {
            val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: continue
            byUrl.remove(url)
            byUrl[url] = item
        }
        if (byUrl.size > maxHistoryBatch)
            return null
        return JsonObject(mapOf("items" to JsonArray(byUrl.values.toList())))
    }

    @Synchronized
    fun peek(): OutboxOp? = ops.firstOrNull()

    @Synchronized
    fun remove(id: Long) {
        if (ops.removeAll { it.id == id })
            persist()
    }

    /**
     * The first op, with its attempt counted. Once an op has been handed out for sending nothing is merged into it any
     * more, so what the server receives is exactly what gets removed on success.
     */
    @Synchronized
    fun takeForSend(): OutboxOp? {
        val first = ops.firstOrNull() ?: return null
        ops[0] = first.copy(attempts = first.attempts + 1)
        persist()
        return ops[0]
    }

    @Synchronized
    fun size(): Int = ops.size

    @Synchronized
    fun hasQueueOps(): Boolean = ops.any { it.kind in OutboxOp.QUEUE_KINDS }

    @Synchronized
    fun all(): List<OutboxOp> = ops.toList()
}
