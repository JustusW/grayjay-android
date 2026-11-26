package com.futo.platformplayer.e2e.steps

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.futo.platformplayer.Settings
import com.futo.platformplayer.activities.AddSourceActivity
import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.models.PlatformAuthorLink
import com.futo.platformplayer.api.media.models.Thumbnails
import com.futo.platformplayer.api.media.models.channels.SerializedChannel
import com.futo.platformplayer.api.media.models.contents.ContentType
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import com.futo.platformplayer.e2e.support.App
import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.NavUi
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.fragment.mainactivity.main.SettingsFragment
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StatePlaylists
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.subfeed.SubfeedBridge
import com.futo.platformplayer.subfeed.SubfeedPeriodicWorker
import com.futo.platformplayer.subfeed.SubfeedSettings
import com.futo.platformplayer.subfeed.SubfeedStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The subfeed bridge and plugin against the REAL subfeed server (the combined gate's gate_server.py on the host: the
 * real app, worker and migrations on a throw-away PostgreSQL, a fake YouTube), reached at its real name
 * http://subfeed.wingert.internal through the emulator's hosts file and adb reverse tcp:80. Server-side changes go
 * through the server's own API (the page's single removals; Ciel's admin key, instrumentation argument
 * subfeedAdminKey); /_ctl/ only empties the database, changes the fake YouTube and reports the database's state.
 */
class SubfeedSteps {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private var before: List<Int> = listOf()
    private var finished: List<Int> = listOf()
    private var expectedAfterPull: List<String> = listOf()
    private var lastAnswer: Answer? = null

    //region The server
    private class Answer(val code: Int, val json: JsonElement?)

    private fun call(method: String, path: String, body: JsonElement? = null, admin: Boolean = false): Answer {
        //Marked so the server log tells the steps' own requests from the app's
        val builder = Request.Builder().url(SERVER + path).header("X-Gate-Step", "1")
        if (admin)
            builder.header("X-Subfeed-Writer", InstrumentationRegistry.getArguments().getString("subfeedAdminKey")
                ?: throw IllegalStateException("instrumentation argument subfeedAdminKey missing"))
        val requestBody = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody)
            "PUT" -> builder.put(requestBody)
            else -> throw IllegalArgumentException(method)
        }
        http.newCall(builder.build()).execute().use {
            val text = it.body.string()
            return Answer(it.code, if (text.isBlank()) null else Json.parseToJsonElement(text))
        }
    }

    private fun state() = call("GET", "/_ctl/state").json!!.jsonObject
    private fun strings(json: JsonElement?) = json!!.jsonArray.map { it.jsonPrimitive.content }
    private fun queueItems() = call("GET", "/api/v1/queue").json!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }
    private fun serverQueue(): List<String> = queueItems().map { it["url"]!!.jsonPrimitive.content }
    private fun serverVersion(): Long = call("GET", "/api/v1/queue").json!!.jsonObject["version"]!!.jsonPrimitive.longOrNull!!
    private fun body(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
    //endregion

    //region The videos and channels (gate_server.py seeds the same)
    private fun id(n: Int) = "sf%09d".format(n)
    private fun title(n: Int) = "Gate video %03d".format(n)
    private fun numberOf(title: String) = title.removePrefix("Gate video ").trim().toInt()
    private fun channelId(n: Int) = "UCsubfeedGateChannel%04d".format(n)
    private fun channelUrl(n: Int) = "https://www.youtube.com/channel/" + channelId(n)
    private fun channelOf(video: Int): Int = when (video) {
        in 16..25 -> 1
        in 200..207 -> 2
        in 30..33, in 300..303 -> 3
        else -> 9
    }

    /** As Grayjay keeps it: YouTube watch URLs, except a youtu.be one, a mobile one and a video from elsewhere. */
    private fun localUrl(n: Int) = when (n) {
        50 -> "https://youtu.be/${id(n)}"
        51 -> "https://m.youtube.com/watch?v=${id(n)}&t=5"
        52 -> "https://media.example/watch/52"
        else -> "https://www.youtube.com/watch?v=${id(n)}"
    }

    /** As the server keeps it (YouTube video URLs as https://www.youtube.com/watch?v=<id>). */
    private fun serverUrl(n: Int) = if (n == 52) localUrl(n) else "https://www.youtube.com/watch?v=${id(n)}"

    private fun video(n: Int): SerializedPlatformVideo {
        val ch = channelOf(n)
        return SerializedPlatformVideo(
            ContentType.MEDIA,
            id = PlatformID("YouTube", id(n)),
            name = title(n),
            thumbnails = Thumbnails(),
            author = PlatformAuthorLink(PlatformID("YouTube", channelId(ch)), "Gate Channel $ch", channelUrl(ch)),
            datetime = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC).plusHours(n.toLong()),
            url = localUrl(n),
            shareUrl = localUrl(n),
            duration = 600,
            viewCount = 100
        )
    }

    private fun channel(n: Int) = SerializedChannel(PlatformID("YouTube", channelId(n)), "Gate Channel $n", null, null, 0,
        null, channelUrl(n), mapOf())

    private fun watchLater() = StatePlaylists.instance.getWatchLater().map { it.url }
    private fun local(numbers: List<Int>) = numbers.map { localUrl(it) }
    private fun server(numbers: List<Int>) = numbers.map { serverUrl(it) }
    private fun serverForm(urls: List<String>) = urls.map { url -> (before + 400).firstOrNull { localUrl(it) == url }?.let { serverUrl(it) } ?: url }

    private fun shot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /data/local/tmp/e2e/subfeed").close()
        Thread.sleep(500)
        automation.executeShellCommand("screencap -p /data/local/tmp/e2e/subfeed/$name.png").close()
        Thread.sleep(1500)
    }
    //endregion

    //region Arrange
    @Given("^the subfeed server is empty and its fake YouTube has (\\d+) channels$")
    fun theServerIsEmpty(count: Int) {
        assertEquals(3, count)
        assertEquals(200, call("POST", "/_ctl/reset").code)
        assertEquals(true, call("GET", "/api/v1/queue").json!!.jsonObject["importing"]!!.jsonPrimitive.booleanOrNull)
    }

    @Given("^my Watch Later holds (\\d+) YouTube videos in a scrambled order$")
    fun myWatchLaterHolds(count: Int) {
        val numbers = (0 until count).map { 16 + (it * 37) % count }
        Backdoor.onMain { StatePlaylists.instance.updateWatchLater(ArrayList(numbers.map { video(it) })) }
        Wait.until("Watch Later to hold $count videos") { assertEquals(local(numbers), watchLater()) }
        before = numbers
    }

    @Given("^I have watched (\\d+) of them to the end$")
    fun iHaveWatchedOfThemToTheEnd(count: Int) {
        val chosen = before.filterIndexed { i, _ -> i % 20 == 3 }.take(count)
        for (n in chosen) {
            val v = StatePlaylists.instance.getWatchLater().first { it.url == localUrl(n) }
            val index = StateHistory.instance.getHistoryByVideo(v, true)!!
            StateHistory.instance.updateHistoryPosition(v, index, true, v.duration, null, false)
        }
        finished = chosen
        assertEquals(count, finished.size)
    }

    @Given("^I am subscribed to the (\\d+) channels$")
    fun iAmSubscribedToTheChannels(count: Int) {
        for (n in 1..count)
            StateSubscriptions.instance.addSubscription(channel(n))
    }

    @Given("subfeed is on and in sync")
    fun subfeedIsOnAndInSync() {
        iSwitchSubfeedOn()
        assertEquals(server(before), serverQueue())
    }

    @Given("the server has polled the channels")
    fun theServerHasPolledTheChannels() {
        call("POST", "/_ctl/poll_now")
        Wait.until("the server's worker to poll the 3 channels", 120_000) {
            val s = state()
            assertTrue(s["channels"].toString(), s["channels"]!!.jsonArray.count { it.jsonObject["polled"]!!.jsonPrimitive.booleanOrNull == true } >= 3)
            assertTrue(s["feed_videos"].toString(), (s["feed_videos"]!!.jsonPrimitive.longOrNull ?: 0) >= 26)
        }
    }
    //endregion

    //region Act
    @When("I switch subfeed on")
    fun iSwitchSubfeedOn() {
        Settings.instance.subfeed.enabled = true
        //The real default address: http://subfeed.wingert.internal
        assertEquals(SubfeedSettings.DEFAULT_URL, Settings.instance.subfeed.serverUrl)
        Settings.instance.saveBlocking()
        NavUi.openWatchLater()
        shot("01-watch-later-before-first-sync")
        val ok = runBlocking { SubfeedBridge.instance.sync("e2e", interactive = true) }
        assertTrue("first sync failed: ${SubfeedBridge.instance.statusText(target)}", ok)
        assertTrue(SubfeedStore.state(target).firstSyncDone)
    }

    @When("^the server is sent its queue without (\\d+) videos$")
    fun theServerIsSentItsQueueWithout(count: Int) {
        val gone = before.filter { it !in finished }.filterIndexed { i, _ -> i % 17 == 5 }.take(count).toSet()
        lastAnswer = call("PUT", "/api/v1/queue", JsonObject(mapOf(
            "base_version" to JsonPrimitive(serverVersion()),
            "items" to JsonArray(server(before.filter { it !in gone }).map { JsonPrimitive(it) })
        )))
    }

    @When("^(\\d+) unfinished videos? (?:is|are) removed on the server(?: one by one)?$")
    fun unfinishedVideosAreRemovedOnTheServer(count: Int) {
        val gone = before.filter { it !in finished }.filterIndexed { i, _ -> i % 17 == 5 }.take(count)
        assertEquals(count, gone.size)
        for (n in gone)
            assertEquals(200, call("POST", "/api/v1/queue/remove", body("url" to serverUrl(n))).code)
        expectedAfterPull = server(before - gone.toSet())
        assertEquals(expectedAfterPull, serverQueue())
    }

    @When("subfeed pulls the server's queue")
    fun subfeedPulls() {
        runBlocking { SubfeedBridge.instance.sync("e2e-pull", interactive = true) }
        Thread.sleep(500)
        shot("pull-" + System.currentTimeMillis())
    }

    @When("^Watch Later is cut down to (\\d+) videos on the phone$")
    fun watchLaterIsCutDown(count: Int) {
        val kept = StatePlaylists.instance.getWatchLater().take(count)
        Backdoor.onMain { StatePlaylists.instance.updateWatchLater(ArrayList(kept), true) }
        expectedAfterPull = serverForm(kept.map { it.url })
    }

    @When("I restore Watch Later from the server snapshot in the settings")
    fun iRestoreFromTheServerSnapshot() {
        val device = device //UiScrollable needs the UiDevice singleton set up first
        Backdoor.onMain { App.activity().navigate(App.activity().getFragment<SettingsFragment>()) }
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        Wait.until("the subfeed settings") { assertTrue(scrollable.scrollTextIntoView("subfeed sync")) }
        shot("05-settings-subfeed-section")
        Wait.until("the restore button") { assertTrue(scrollable.scrollTextIntoView("subfeed: restore Watch Later from server snapshot")) }
        shot("06-settings-restore-entries")
        device.findObject(By.text("subfeed: restore Watch Later from server snapshot")).click()
        val newest = device.wait(Until.findObject(By.text("Newest")), 15_000)
        assertNotNull("the snapshot choice", newest)
        shot("07-server-snapshot-choice")
        newest.click()
        val restore = device.wait(Until.findObject(By.text("Restore")), 15_000)
        assertNotNull("the restore confirmation", restore)
        shot("08-restore-confirmation")
        restore.click()
        Wait.until("Watch Later to be restored", 30_000) { assertEquals(local(before), watchLater()) }
        Thread.sleep(800)
        shot("09-restored")
    }

    @When("^I open the plugin install page for \"([^\"]+)\"$")
    fun iOpenThePluginInstallPage(url: String) {
        //What Grayjay's "install from URL" (and a grayjay://plugin/ link) opens
        target.startActivity(Intent(target, AddSourceActivity::class.java).setData(Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull("the install page", device.wait(Until.findObject(By.text("Install")), 30_000))
        Thread.sleep(1500)
    }

    @When("I install it")
    fun iInstallIt() {
        device.findObject(By.text("Install")).click()
        Wait.until("the subfeed plugin to be installed and enabled", 30_000) {
            assertTrue(StatePlatform.instance.getEnabledClients().any { it.id == PLUGIN_ID })
        }
        //Home from the subfeed plugin alone (the e2e fixture source switched off)
        runBlocking { StatePlatform.instance.selectClients(PLUGIN_ID) }
    }

    @When("^\"Gate Channel (\\d+)\" is made a priority channel with the admin key$")
    fun isMadeAPriorityChannel(n: Int) {
        //Not without the key: priority is Ciel's (admin)
        assertEquals(403, call("POST", "/api/v1/priority", body("channel" to channelId(n))).code)
        val r = call("POST", "/api/v1/priority", body("channel" to channelId(n)), admin = true)
        assertEquals(r.json.toString(), 200, r.code)
        assertEquals(true, r.json!!.jsonObject["channel"]!!.jsonObject["priority"]!!.jsonPrimitive.booleanOrNull)
    }

    @When("^\"Gate Channel (\\d+)\" uploads \"([^\"]+)\"$")
    fun uploads(n: Int, title: String) {
        assertEquals(200, call("POST", "/_ctl/feed_add", body("channel" to channelId(n), "video_id" to PRIORITY_ID, "title" to title)).code)
    }

    @When("the 15-minute subfeed worker runs")
    fun the15MinuteWorkerRuns() {
        //The periodic worker's own class, run once now instead of waiting up to 15 minutes
        val request = OneTimeWorkRequestBuilder<SubfeedPeriodicWorker>().build()
        val wm = WorkManager.getInstance(target)
        wm.enqueue(request).result.get()
        Wait.until("the subfeed worker to finish", 60_000) {
            assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(request.id).get()?.state)
        }
    }

    @When("^I subscribe to \"Gate Channel (\\d+)\" and unsubscribe from \"Gate Channel (\\d+)\"$")
    fun iSubscribeAndUnsubscribe(add: Int, remove: Int) {
        StateSubscriptions.instance.addSubscription(channel(add))
        StateSubscriptions.instance.removeSubscription(channelUrl(remove), true)
    }

    @When("^I watch \"([^\"]+)\" up to (\\d+) seconds$")
    fun iWatchUpTo(name: String, seconds: Long) {
        val v = StatePlaylists.instance.getWatchLater().first { it.name == name }
        val index = StateHistory.instance.getHistoryByVideo(v, true)!!
        StateHistory.instance.updateHistoryPosition(v, index, true, seconds, null, true)
    }

    @When("^I add \"([^\"]+)\" to Watch Later and remove \"([^\"]+)\" from it$")
    fun iAddAndRemoveWatchLater(add: String, remove: String) {
        Backdoor.onMain {
            StatePlaylists.instance.addToWatchLater(video(numberOf(add)), true)
            StatePlaylists.instance.removeFromWatchLater(localUrl(numberOf(remove)), true)
        }
    }
    //endregion

    //region Assert
    @Then("^a local backup with the (\\d+) Watch Later videos in order was written before anything was sent$")
    fun aLocalBackupWasWritten(count: Int) {
        val file = File(SubfeedStore.state(target).backupFile!!)
        assertEquals(target.getExternalFilesDir(null)!!.absolutePath, file.parentFile!!.absolutePath)
        assertTrue(file.name.startsWith("subfeed-backup-"))
        val backup = Json.parseToJsonElement(file.readText()).jsonObject
        val wl = backup["watch_later"]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(count, wl.size)
        assertEquals(local(before), wl)
        assertTrue(backup["history"]!!.jsonArray.size >= finished.size)
        assertEquals(3, backup["subscriptions"]!!.jsonArray.size)
        //The first request the server ever saw is the snapshot: nothing was sent before the backup existed
        assertEquals("POST /api/v1/sync/snapshot 201", strings(state()["requests"]).first())
    }

    @Then("^the server stored a snapshot with the (\\d+) Watch Later videos in order$")
    fun theServerStoredASnapshot(count: Int) {
        //Through the restore path's own routes: the list, then the snapshot itself
        val list = call("GET", "/api/v1/sync/snapshots").json!!.jsonObject["snapshots"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, list.size)
        assertEquals(count.toLong(), list[0]["counts"]!!.jsonObject["watch_later"]!!.jsonPrimitive.longOrNull)
        assertTrue(list[0]["taken_at"] !is JsonNull)
        val id = list[0]["id"]!!.jsonPrimitive.longOrNull
        val data = call("GET", "/api/v1/sync/snapshots/$id").json!!.jsonObject["data"]!!.jsonObject
        assertEquals(local(before), data["watch_later"]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content })
    }

    @Then("the server's queue is my Watch Later, order included")
    fun theServersQueueIsMyWatchLater() {
        assertEquals(server(before), serverQueue())
        assertFalse(state()["importing"]!!.jsonPrimitive.booleanOrNull!!)
    }

    @Then("my Watch Later is exactly what it was before, order included")
    fun myWatchLaterIsUnchanged() {
        Thread.sleep(2000)
        assertEquals(local(before), watchLater())
        NavUi.openWatchLater()
        shot("watch-later-" + System.currentTimeMillis())
    }

    @Then("^the server has the (\\d+) channels and the (\\d+) finished videos as watched$")
    fun theServerHasTheChannelsAndHistory(count: Int, finishedCount: Int) {
        val s = state()
        val active = s["channels"]!!.jsonArray.map { it.jsonObject }.filter { it["removed"]!!.jsonPrimitive.booleanOrNull == false }
        assertEquals((1..count).map { channelId(it) }, active.map { it["channel_id"]!!.jsonPrimitive.content })
        assertTrue(active.all { it["phone_known"]!!.jsonPrimitive.booleanOrNull == true })
        val watched = s["history"]!!.jsonArray.map { it.jsonObject }.filter { it["watched"]!!.jsonPrimitive.booleanOrNull == true }
        assertEquals(finished.map { id(it) }.sorted(), watched.map { it["video_id"]!!.jsonPrimitive.content }.sorted())
        assertEquals(finishedCount, watched.size)
        assertTrue(watched.all { it["position_s"]!!.jsonPrimitive.doubleOrNull == 600.0 })
    }

    @Then("the server refuses the bulk removal and keeps its queue")
    fun theServerRefusesTheBulkRemoval() {
        val answer = lastAnswer!!
        val json = answer.json!!.jsonObject
        assertEquals(json.toString().take(300), 409, answer.code)
        assertEquals("bulk removal refused", json["detail"]!!.jsonPrimitive.content)
        assertEquals(5L, json["would_remove"]!!.jsonPrimitive.longOrNull)
        assertEquals(server(before), serverQueue())
        //Ciel hears of it
        val notices = call("GET", "/api/v1/notices", admin = true).json!!.jsonObject["notices"]!!.jsonArray
        assertEquals("queue_bulk_refused", notices.last().jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Then("subfeed reports a refused bulk change")
    fun subfeedReportsARefusal() {
        assertNotNull(SubfeedStore.state(target).lastRefusal)
        //The server keeps its version; the phone neither adopted it nor pushed its own back
        assertEquals(expectedAfterPull, serverQueue())
    }

    @Then("my Watch Later lost exactly that video and kept the order of the rest")
    fun myWatchLaterLostExactlyThatVideo() {
        Wait.until("the pull to be applied") { assertEquals(expectedAfterPull, serverForm(watchLater())) }
        assertEquals(before.size - 1, watchLater().size)
        assertNull(SubfeedStore.state(target).lastRefusal)
        NavUi.openWatchLater()
        shot("03-watch-later-after-single-removal")
    }

    @Then("^the server's queue follows to the same (\\d+) videos$")
    fun theServersQueueFollowsToTheSame(count: Int) {
        Wait.until("the server's queue to follow", 90_000) { assertEquals(expectedAfterPull, serverQueue()) }
        assertEquals(count, expectedAfterPull.size)
    }

    @Then("the server's queue follows to my Watch Later")
    fun theServersQueueFollowsToMyWatchLater() {
        Wait.until("the server's queue to follow", 90_000) { assertEquals(serverForm(watchLater()), serverQueue()) }
    }

    @Then("the install page shows the subfeed plugin without a signature warning")
    fun theInstallPageShowsNoSignatureWarning() {
        assertNotNull("the plugin name", device.findObject(By.text("subfeed")))
        assertNotNull("the url access", device.findObject(By.textContains("subfeed.wingert.internal")))
        for (warning in listOf("Missing Signature", "Invalid Signature", "Different Author"))
            assertNull("warning $warning shown", device.findObject(By.text(warning)))
        shot("10-plugin-install-page")
    }

    @Then("Home shows the subfeed feed with my queued videos marked")
    fun homeShowsTheSubfeedFeed() {
        NavUi.openTab("Home")
        //Newest first: Gate video 016 (Gate Channel 1, in Watch Later = queued), 200 (Gate Channel 2, not queued)
        Wait.until("the subfeed feed on Home", 60_000) {
            assertNotNull(device.findObject(By.text("▶ " + title(16))))
            assertNotNull(device.findObject(By.text(title(200))))
        }
        Thread.sleep(1500)
        shot("11-home-subfeed-feed")
    }

    @Then("^the server puts \"([^\"]+)\" at the top of its queue$")
    fun theServerPutsItAtTheTop(title: String) {
        Wait.until("the priority video at the top of the server's queue", 150_000) {
            val q = queueItems()
            assertEquals(PRIORITY_ID, q.first()["video_id"]!!.jsonPrimitive.content)
            assertEquals(title, q.first()["title"]!!.jsonPrimitive.content)
            assertEquals(true, q.first()["priority"]!!.jsonPrimitive.booleanOrNull)
            assertEquals(before.size + 1, q.size)
        }
    }

    @Then("^a notification for \"([^\"]+)\" is shown$")
    fun aNotificationIsShown(title: String) {
        device.openNotification()
        assertNotNull("the notification", device.wait(Until.findObject(By.textContains(title)), 20_000))
        assertNotNull(device.findObject(By.textContains("Gate Channel 1")))
        Thread.sleep(1000)
        shot("12-notification-shade")
        device.pressBack()
        Thread.sleep(800)
    }

    @Then("^my Watch Later starts with \"([^\"]+)\"$")
    fun myWatchLaterStartsWith(title: String) {
        Wait.until("the priority video at the top of Watch Later", 30_000) {
            assertEquals(title, StatePlaylists.instance.getWatchLater().first().name)
        }
        assertEquals(local(before), watchLater().drop(1))
        NavUi.openWatchLater()
        shot("13-watch-later-priority-on-top")
    }

    @Then("^the server knows \"Gate Channel (\\d+)\" and that \"Gate Channel (\\d+)\" is gone$")
    fun theServerKnowsTheSubscriptions(added: Int, removed: Int) {
        Wait.until("the subscription changes to arrive", 60_000) {
            val chans = state()["channels"]!!.jsonArray.map { it.jsonObject }.associateBy { it["channel_id"]!!.jsonPrimitive.content }
            assertEquals(false, chans[channelId(added)]?.get("removed")?.jsonPrimitive?.booleanOrNull)
            assertEquals(true, chans[channelId(removed)]?.get("removed")?.jsonPrimitive?.booleanOrNull)
        }
    }

    @Then("^the server got the position (\\d+) of \"([^\"]+)\"$")
    fun theServerGotThePosition(seconds: Long, name: String) {
        Wait.until("the position to arrive", 60_000) {
            val h = state()["history"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["video_id"]!!.jsonPrimitive.content == id(numberOf(name)) }
            assertNotNull(h)
            assertEquals(seconds.toDouble(), h!!["position_s"]!!.jsonPrimitive.doubleOrNull)
        }
    }
    //endregion

    companion object {
        const val SERVER = SubfeedSettings.DEFAULT_URL
        const val PLUGIN_ID = "37b8a295-ddde-4e66-b21f-9ed918151ec7"
        const val PRIORITY_ID = "sfprio00001"
    }
}
