package com.futo.platformplayer.subfeed

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QueueGuardTests {
    private val local = (1..150).map { "v$it" }
    private val nothingFinished: (String) -> Boolean = { false }

    @Test
    fun sameListIsNoChange() {
        assertEquals(QueueDecision.NoChange, QueueGuard.decide(local, local.toList(), nothingFinished))
    }

    @Test
    fun aReorderIsApplied() {
        val server = local.reversed()
        val decision = QueueGuard.decide(local, server, nothingFinished) as QueueDecision.Apply
        assertEquals(server, decision.order)
        assertTrue(decision.removed.isEmpty())
    }

    @Test
    fun upToThreeRemovalsAreApplied() {
        val server = local - setOf("v3", "v50", "v149")
        val decision = QueueGuard.decide(local, server, nothingFinished) as QueueDecision.Apply
        assertEquals(listOf("v3", "v50", "v149"), decision.removed)
        assertEquals(server, decision.order)
    }

    @Test
    fun fourUnfinishedRemovalsAreRefused() {
        val server = local - setOf("v1", "v2", "v3", "v4")
        val decision = QueueGuard.decide(local, server, nothingFinished)
        assertTrue(decision is QueueDecision.Refuse)
        assertEquals(4, (decision as QueueDecision.Refuse).unfinishedRemovals.size)
    }

    @Test
    fun anEmptiedServerQueueIsRefused() {
        assertTrue(QueueGuard.decide(local, listOf(), nothingFinished) is QueueDecision.Refuse)
    }

    @Test
    fun finishedVideosDontCountTowardsTheLimit() {
        val finished = setOf("v1", "v2", "v3", "v4", "v5", "v6")
        val server = local - finished - setOf("v7")
        val decision = QueueGuard.decide(local, server, { it in finished }) as QueueDecision.Apply
        assertEquals(7, decision.removed.size)
    }

    @Test
    fun refusalAlsoKeepsTheOrder() {
        //A bulk removal combined with a reorder: neither is applied
        val server = (local - setOf("v1", "v2", "v3", "v4", "v5")).reversed()
        assertTrue(QueueGuard.decide(local, server, nothingFinished) is QueueDecision.Refuse)
    }

    @Test
    fun serverAdditionsAreReported() {
        val server = listOf("new1") + local + listOf("new2")
        val decision = QueueGuard.decide(local, server, nothingFinished) as QueueDecision.Apply
        assertEquals(listOf("new1", "new2"), decision.added)
    }
}

class WatchLaterDiffTests {
    /** Applies [changes] the way the server does, to check they really turn base into local. */
    private fun replay(base: List<String>, changes: List<QueueChange>): List<String> {
        val q = base.toMutableList()
        for (c in changes) when (c) {
            is QueueChange.Remove -> q.remove(c.url)
            is QueueChange.Add -> if (c.top) q.add(0, c.url) else q.add(c.url)
            is QueueChange.Reorder -> { q.clear(); q.addAll(c.order) }
        }
        return q
    }

    @Test
    fun noChangeNoOps() {
        assertTrue(WatchLaterDiff.changes(listOf("a", "b"), listOf("a", "b")).isEmpty())
    }

    @Test
    fun singleRemoval() {
        assertEquals(listOf(QueueChange.Remove("b")), WatchLaterDiff.changes(listOf("a", "b", "c"), listOf("a", "c")))
    }

    @Test
    fun additionAtStartAndEnd() {
        val changes = WatchLaterDiff.changes(listOf("a", "b"), listOf("x", "a", "b", "y"))
        assertEquals(listOf(QueueChange.Add("x", true), QueueChange.Add("y", false)), changes)
    }

    @Test
    fun anAdditionInTheMiddleIsAddedAtTheBottomThenReordered() {
        //The server only adds at the top or the bottom ("position": "top" | "bottom")
        val changes = WatchLaterDiff.changes(listOf("a", "b", "c"), listOf("a", "x", "b", "c"))
        assertEquals(listOf(QueueChange.Add("x", false), QueueChange.Reorder(listOf("a", "x", "b", "c"))), changes)
        assertEquals(listOf("a", "x", "b", "c"), replay(listOf("a", "b", "c"), changes))
    }

    @Test
    fun reorderOnlyWhenNeeded() {
        val changes = WatchLaterDiff.changes(listOf("a", "b", "c"), listOf("c", "a", "b"))
        assertEquals(listOf(QueueChange.Reorder(listOf("c", "a", "b"))), changes)
    }

    @Test
    fun manyRemovalsAreSingleOpsNeverABulkPut() {
        val base = (1..150).map { "v$it" }
        val local = base.filterIndexed { i, _ -> i % 2 == 0 }
        val changes = WatchLaterDiff.changes(base, local)
        assertEquals(75, changes.count { it is QueueChange.Remove })
        assertTrue(changes.none { it is QueueChange.Reorder })
        assertEquals(local, replay(base, changes))
    }

    @Test
    fun mixedChangesReplayToLocal() {
        val base = (1..20).map { "v$it" }
        val local = listOf("n1") + base.drop(3).reversed() + listOf("n2")
        assertEquals(local, replay(base, WatchLaterDiff.changes(base, local)))
    }

    @Test
    fun reorderNeverRemovesWhatTheServerAddedMeanwhile() {
        val order = WatchLaterDiff.reorderOnto(listOf("c", "b", "a"), listOf("a", "b", "s1", "c"))
        assertEquals(listOf("c", "b", "a", "s1"), order)
    }

    @Test
    fun reorderSkipsWhatTheServerNoLongerHas() {
        assertEquals(listOf("b", "a"), WatchLaterDiff.reorderOnto(listOf("x", "b", "a"), listOf("a", "b")))
    }
}

class HistoryThrottleTests {
    @Test
    fun firstUpdateGoesOutThenThirtySecondsQuiet() {
        val t = HistoryThrottle<String>(30_000)
        assertEquals(listOf("p5"), t.offer("v", "p5", 5, 600, 0))
        assertEquals(listOf<String>(), t.offer("v", "p10", 10, 600, 5_000))
        assertEquals(listOf<String>(), t.offer("v", "p15", 15, 600, 10_000))
        assertEquals(1, t.pendingCount())
        //Only the newest held-back position comes out once the interval has passed
        assertEquals(listOf<String>(), t.due(29_999))
        assertEquals(listOf("p15"), t.due(30_000))
        assertEquals(0, t.pendingCount())
    }

    @Test
    fun atMostOnePerThirtySecondsOverALongPlayback() {
        val t = HistoryThrottle<Long>(30_000)
        var sent = 0
        var now = 0L
        for (pos in 0L until 600L step 5) {
            now = pos * 1000
            sent += t.offer("v", pos, pos, 6000, now).size + t.due(now).size
        }
        //600 s of updates every 5 s -> 20 sends, not 120
        assertEquals(20, sent)
    }

    @Test
    fun differentVideosAreThrottledSeparately() {
        val t = HistoryThrottle<String>(30_000)
        assertEquals(1, t.offer("a", "a1", 1, 600, 0).size)
        assertEquals(1, t.offer("b", "b1", 1, 600, 1_000).size)
    }

    @Test
    fun crossingNinetyPercentGoesOutImmediately() {
        val t = HistoryThrottle<String>(30_000)
        t.offer("v", "p80", 80, 100, 0)
        assertEquals(listOf("p91"), t.offer("v", "p91", 91, 100, 5_000))
        //...but only the first time
        assertEquals(listOf<String>(), t.offer("v", "p95", 95, 100, 10_000))
    }

    @Test
    fun pauseFlushesEverythingPending() {
        val t = HistoryThrottle<String>(30_000)
        t.offer("v", "p1", 1, 600, 0)
        t.offer("v", "p6", 6, 600, 5_000)
        assertEquals(listOf("p6"), t.flush(6_000))
        assertEquals(null, t.nextDueInMs(6_000))
    }
}

class OutboxTests {
    private fun tempFile(): File = File(Files.createTempDirectory("outbox").toFile(), "outbox.json")
    private fun history(vararg pairs: Pair<String, Long>) =
        JsonObject(mapOf("items" to JsonArray(pairs.map { JsonObject(mapOf("url" to JsonPrimitive(it.first), "position_s" to JsonPrimitive(it.second))) })))

    @Test
    fun survivesARestartInOrder() {
        val file = tempFile()
        val a = Outbox(file)
        a.add(OutboxOp.KIND_QUEUE_ADD, "POST", "/add", JsonObject(mapOf("url" to JsonPrimitive("x"))))
        a.add(OutboxOp.KIND_QUEUE_REMOVE, "POST", "/remove", JsonObject(mapOf("url" to JsonPrimitive("y"))))
        val b = Outbox(file)
        assertEquals(listOf(OutboxOp.KIND_QUEUE_ADD, OutboxOp.KIND_QUEUE_REMOVE), b.all().map { it.kind })
        assertTrue(b.hasQueueOps())
    }

    @Test
    fun sentOpsAreRemovedAndNewIdsDontCollide() {
        val file = tempFile()
        val a = Outbox(file)
        val first = a.add(OutboxOp.KIND_SUBSCRIPTIONS, "POST", "/s", null)
        a.add(OutboxOp.KIND_SUBSCRIPTIONS, "POST", "/s", null)
        val taken = a.takeForSend()!!
        assertEquals(first.id, taken.id)
        assertEquals(1, taken.attempts)
        a.remove(taken.id)
        val b = Outbox(file)
        assertEquals(1, b.size())
        val third = b.add(OutboxOp.KIND_SUBSCRIPTIONS, "POST", "/s", null)
        assertTrue(b.all().map { it.id }.distinct().size == 2 && third.id > first.id)
    }

    @Test
    fun consecutiveHistoryBatchesMergeNewestWins() {
        val outbox = Outbox(tempFile())
        outbox.add(OutboxOp.KIND_HISTORY, "POST", "/h", history("a" to 10, "b" to 5))
        outbox.add(OutboxOp.KIND_HISTORY, "POST", "/h", history("a" to 40))
        assertEquals(1, outbox.size())
        val items = outbox.peek()!!.body!!.jsonObject["items"]!!.jsonArray
        assertEquals(listOf("b", "a"), items.map { it.jsonObject["url"]!!.jsonPrimitive.content })
        assertEquals("40", items.last().jsonObject["position_s"]!!.jsonPrimitive.content)
    }

    @Test
    fun nothingIsMergedIntoAnOpBeingSent() {
        val outbox = Outbox(tempFile())
        outbox.add(OutboxOp.KIND_HISTORY, "POST", "/h", history("a" to 10))
        val inFlight = outbox.takeForSend()!!
        outbox.add(OutboxOp.KIND_HISTORY, "POST", "/h", history("b" to 3))
        outbox.remove(inFlight.id)
        //b must still be waiting
        assertEquals(1, outbox.size())
        assertEquals("b", outbox.peek()!!.body!!.jsonObject["items"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun historyDoesntMergeAcrossOtherOps() {
        val outbox = Outbox(tempFile())
        outbox.add(OutboxOp.KIND_HISTORY, "POST", "/h", history("a" to 10))
        outbox.add(OutboxOp.KIND_QUEUE_REMOVE, "POST", "/r", null)
        outbox.add(OutboxOp.KIND_HISTORY, "POST", "/h", history("a" to 20))
        assertEquals(3, outbox.size())
    }

    @Test
    fun aDamagedFileStartsEmptyAndIsKept() {
        val file = tempFile()
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        val outbox = Outbox(file)
        assertEquals(0, outbox.size())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("outbox.json.damaged-") })
    }
}

class SubfeedUrlsTests {
    //The cases of subfeed's urls.normalize_video_url (the server compares queue URLs in this form)
    @Test
    fun youtubeVideoUrlsBecomeTheWatchUrl() {
        val id = "dQw4w9WgXcQ"
        val watch = "https://www.youtube.com/watch?v=$id"
        for (url in listOf(watch, "https://youtu.be/$id", "youtu.be/$id", "https://m.youtube.com/watch?v=$id&t=5",
                "http://youtube.com/watch?feature=share&v=$id", "https://www.youtube.com/shorts/$id",
                "https://music.youtube.com/watch?v=$id", "https://www.youtube.com/live/$id?si=x", "  $watch  ", id))
            assertEquals(url, watch, SubfeedUrls.normalize(url))
    }

    @Test
    fun otherUrlsStayAsTheyAre() {
        for (url in listOf("https://peertube.example/w/abc", "https://fixture.test/watch/07", "https://www.youtube.com/watch?v=short",
                "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx", "https://evil.example/watch?v=dQw4w9WgXcQ",
                "youtube.com/watch?v=dQw4w9WgXcQ"))
            assertEquals(url, SubfeedUrls.normalize(url))
    }
}

class QueueImportPlanTests {
    @Test
    fun partsCarryTheirOffsetAndCoverEverythingInOrder() {
        val urls = (1..2500).map { "v$it" }
        val parts = QueueImportPlan.parts(urls)
        assertEquals(listOf(0, 1000, 2000), parts.map { it.first })
        assertEquals(urls, parts.flatMap { it.second })
    }

    @Test
    fun anEmptyWatchLaterIsStillOneFinalPart() {
        assertEquals(listOf(0 to listOf<String>()), QueueImportPlan.parts(listOf()))
    }
}

class PriorityCursorTests {
    @Test
    fun theNewestFoundAtByInstantNotByText() {
        //12:00 in Berlin is before 11:30 UTC
        assertEquals("2026-11-26T11:30:00+00:00",
            PriorityCursor.next("2026-11-26T10:00:00Z", listOf("2026-11-26T12:00:00+01:00", "2026-11-26T11:30:00+00:00")))
    }

    @Test
    fun nothingNewKeepsTheCursor() {
        assertEquals("2026-11-26T10:00:00Z", PriorityCursor.next("2026-11-26T10:00:00Z", listOf()))
        assertEquals("2026-11-26T10:00:00Z", PriorityCursor.next("2026-11-26T10:00:00Z", listOf("2026-11-26T09:00:00Z", null)))
    }
}
class PlaylistRestorePlanTests {
    private fun p(id: String, name: String, vararg urls: String) = PlaylistShape(id, name, urls.toList())
    private val date = "2026-11-26"

    @Test
    fun aMissingPlaylistComesBackUnderItsId() {
        val plan = PlaylistRestorePlan.plan(listOf(p("a", "queue", "1", "2"), p("b", "queue", "3")), listOf(p("b", "queue", "3")), date)
        assertEquals(listOf<PlaylistRestoreAction>(PlaylistRestoreAction.Recreate("a")), plan)
    }

    @Test
    fun twoPlaylistsWithTheSameNameStayApartById() {
        val snapshot = listOf(p("a", "queue", "1", "2"), p("b", "queue", "3"))
        assertEquals(listOf<PlaylistRestoreAction>(), PlaylistRestorePlan.plan(snapshot, snapshot.reversed(), date))
        //Same name, other id, other contents: "b" is still missing
        assertEquals(listOf<PlaylistRestoreAction>(PlaylistRestoreAction.Recreate("b")),
            PlaylistRestorePlan.plan(snapshot, listOf(p("a", "queue", "1", "2"), p("c", "queue", "3")), date))
    }

    @Test
    fun aChangedPlaylistComesBackAsANewOneNeverOverwritten() {
        for (changed in listOf(p("a", "Fu", "1"), p("a", "Fu", "2", "1"), p("a", "Fu2", "1", "2"), p("a", "Fu", "1", "2", "3"))) {
            val plan = PlaylistRestorePlan.plan(listOf(p("a", "Fu", "1", "2")), listOf(changed), date)
            assertEquals(listOf<PlaylistRestoreAction>(PlaylistRestoreAction.Copy("a", "Fu (restored 2026-11-26)")), plan)
        }
    }

    @Test
    fun aCopyIsntMadeTwice() {
        val local = listOf(p("a", "Fu", "1"), p("x", "Fu (restored 2026-11-20)", "1", "2"))
        assertEquals(listOf<PlaylistRestoreAction>(), PlaylistRestorePlan.plan(listOf(p("a", "Fu", "1", "2")), local, date))
    }

    @Test
    fun nothingIsEverDeleted() {
        //A playlist that is only local is not in the plan at all (the plan has no delete)
        val plan = PlaylistRestorePlan.plan(listOf(), listOf(p("a", "Fu", "1")), date)
        assertTrue(plan.isEmpty())
    }
}
