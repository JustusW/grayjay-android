package com.futo.platformplayer.e2e.steps

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.futo.platformplayer.Settings
import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.models.channels.SerializedChannel
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import com.futo.platformplayer.e2e.support.App
import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.Fixture
import com.futo.platformplayer.e2e.support.NavUi
import com.futo.platformplayer.e2e.support.PlayerControl
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.fragment.mainactivity.main.SettingsFragment
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlayer
import com.futo.platformplayer.states.StatePlaylists
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.subfeed.SubfeedBridge
import com.futo.platformplayer.subfeed.SubfeedStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.util.regex.Pattern

/**
 * The subfeed bridge against the fake server (subfeed/tools/fake_subfeed.py on the host, reached through
 * adb reverse tcp:18765 tcp:18765). /_ctl/ on that server arranges server-side changes and reports what arrived.
 */
class SubfeedSteps {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val http = OkHttpClient()
    private var before: List<String> = listOf()
    private var finished: List<String> = listOf()
    private var expectedAfterPull: List<String> = listOf()
    private var pausedAt: Long = -1

    private fun ctl(path: String, body: JsonElement? = null): JsonObject {
        val builder = Request.Builder().url("$SERVER/_ctl/$path")
        builder.post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { return Json.parseToJsonElement(it.body.string()).jsonObject }
    }

    private fun state() = ctl("state")
    private fun urls(json: JsonElement?) = json!!.jsonArray.map { it.jsonPrimitive.content }
    private fun watchLater() = StatePlaylists.instance.getWatchLater().map { it.url }

    private fun shot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /data/local/tmp/e2e/subfeed").close()
        Thread.sleep(400)
        automation.executeShellCommand("screencap -p /data/local/tmp/e2e/subfeed/$name.png").close()
        Thread.sleep(1200)
    }

    //region Arrange
    @Given("the fake subfeed server is empty")
    fun theFakeSubfeedServerIsEmpty() {
        ctl("reset")
    }

    @Given("^my Watch Later holds (\\d+) videos in a scrambled order$")
    fun myWatchLaterHolds(count: Int) {
        //Videos 16.. (not the playable fixture ones), in an order that is neither by number nor by date
        val numbers = (0 until count).map { 16 + (it * 37) % count }
        StatePlaylists.instance.updateWatchLater(ArrayList(numbers.map { Backdoor.video(it) }))
        //The store saves additions asynchronously
        Wait.until("Watch Later to hold $count videos") { assertEquals(count, watchLater().size) }
        before = watchLater()
        assertEquals(numbers.map { Backdoor.video(it).url }, before)
    }

    @Given("^I have watched (\\d+) of them to the end$")
    fun iHaveWatchedOfThemToTheEnd(count: Int) {
        val videos = StatePlaylists.instance.getWatchLater().filterIndexed { i, _ -> i % 20 == 3 }.take(count)
        for (video in videos) {
            val index = StateHistory.instance.getHistoryByVideo(video, true)!!
            StateHistory.instance.updateHistoryPosition(video, index, true, video.duration, null, false)
        }
        finished = videos.map { it.url }
        assertEquals(count, finished.size)
    }

    @Given("^I am subscribed to (\\d+) channels$")
    fun iAmSubscribedToChannels(count: Int) {
        for (n in 1..count)
            StateSubscriptions.instance.addSubscription(channel("Channel $n"))
    }

    private fun channel(name: String) = SerializedChannel(PlatformID("Fixture", name.lowercase().replace(" ", "-"), Fixture.PLUGIN_ID),
        name, null, null, 0, null, "https://fixture.test/channel/" + name.lowercase().replace(" ", "-"), mapOf())

    @Given("subfeed is on and in sync")
    fun subfeedIsOnAndInSync() {
        iSwitchSubfeedOn()
        assertEquals(before, urls(state()["queue"]))
    }
    //endregion

    //region Act
    @When("I switch subfeed on")
    fun iSwitchSubfeedOn() {
        Settings.instance.subfeed.enabled = true
        Settings.instance.subfeed.serverUrl = SERVER
        Settings.instance.saveBlocking()
        NavUi.openWatchLater()
        shot("01-watch-later-before-first-sync")
        val ok = runBlocking { SubfeedBridge.instance.sync("e2e", interactive = true) }
        assertTrue("first sync failed: ${SubfeedBridge.instance.statusText(target)}", ok)
        assertTrue(SubfeedStore.state(target).firstSyncDone)
    }

    @When("^the server's queue loses (\\d+) unfinished videos?$")
    fun theServersQueueLosesUnfinished(count: Int) {
        val gone = before.filter { it !in finished }.filterIndexed { i, _ -> i % 17 == 5 }.take(count)
        assertEquals(count, gone.size)
        expectedAfterPull = before - gone.toSet()
        ctl("queue", JsonObject(mapOf("items" to JsonArray(expectedAfterPull.map { JsonPrimitive(it) }))))
    }

    @When("^the server's queue loses (\\d+) unfinished videos? and the (\\d+) finished ones$")
    fun theServersQueueLosesUnfinishedAndFinished(count: Int, finishedCount: Int) {
        assertEquals(finishedCount, finished.size)
        val gone = before.filter { it !in finished }.take(count) + finished
        expectedAfterPull = before - gone.toSet()
        ctl("queue", JsonObject(mapOf("items" to JsonArray(expectedAfterPull.map { JsonPrimitive(it) }))))
    }

    @When("the server's queue is reversed")
    fun theServersQueueIsReversed() {
        expectedAfterPull = before.reversed()
        ctl("queue", JsonObject(mapOf("items" to JsonArray(expectedAfterPull.map { JsonPrimitive(it) }))))
    }

    @When("subfeed pulls the server's queue")
    fun subfeedPulls() {
        runBlocking { SubfeedBridge.instance.sync("e2e-pull", interactive = true) }
        shot("pull-" + System.currentTimeMillis())
    }

    @When("^Watch Later is cut down to (\\d+) videos on the phone$")
    fun watchLaterIsCutDown(count: Int) {
        val kept = StatePlaylists.instance.getWatchLater().take(count)
        Backdoor.onMain { StatePlaylists.instance.updateWatchLater(ArrayList(kept), true) }
        expectedAfterPull = kept.map { it.url }
    }

    @When("I restore Watch Later from the server snapshot in the settings")
    fun iRestoreFromTheServerSnapshot() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Backdoor.onMain { App.activity().navigate(App.activity().getFragment<SettingsFragment>()) }
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        Wait.until("the subfeed settings") { assertTrue(scrollable.scrollTextIntoView("subfeed sync")) }
        shot("05-settings-subfeed-section")
        Wait.until("the restore button") { assertTrue(scrollable.scrollTextIntoView("subfeed: restore Watch Later from server snapshot")) }
        shot("06-settings-restore-entries")
        device.findObject(By.text("subfeed: restore Watch Later from server snapshot")).click()
        val restore = device.wait(Until.findObject(By.text(Pattern.compile("(?i)restore"))), 15_000)
        assertNotNull("the restore confirmation", restore)
        shot("07-restore-confirmation")
        restore.click()
        Wait.until("Watch Later to be restored", 20_000) { assertEquals(before, watchLater()) }
        Thread.sleep(800)
        shot("08-restored-toast")
    }

    @When("^I subscribe to \"([^\"]+)\" and unsubscribe from \"([^\"]+)\"$")
    fun iSubscribeAndUnsubscribe(add: String, remove: String) {
        StateSubscriptions.instance.addSubscription(channel(add))
        StateSubscriptions.instance.removeSubscription(channel(remove).url, true)
    }

    @When("^I watch \"([^\"]+)\" for a while and pause$")
    fun iWatchForAWhileAndPause(name: String) {
        val video = Backdoor.video(name)
        Backdoor.onMain { StatePlayer.instance.setQueueWithPosition(listOf(video), StatePlayer.TYPE_QUEUE, 0, true) }
        PlayerControl.waitUntilLoaded()
        Thread.sleep(14_000)
        shot("09-playing-video-01")
        val player = StatePlayer.instance.getPlayerOrCreate(target).player
        Backdoor.onMain { player.pause() }
        Thread.sleep(1500)
        pausedAt = StateHistory.instance.getHistoryPosition(video.url)
        assertTrue("history position after pause: $pausedAt", pausedAt > 5)
    }

    @When("^I add \"([^\"]+)\" to Watch Later and remove \"([^\"]+)\" from it$")
    fun iAddAndRemoveWatchLater(add: String, remove: String) {
        Backdoor.onMain {
            StatePlaylists.instance.addToWatchLater(SerializedPlatformVideo.fromVideo(Backdoor.video(add)), true)
            StatePlaylists.instance.removeFromWatchLater(Backdoor.video(remove).url, true)
        }
    }
    //endregion

    //region Assert
    @Then("^a local backup with the (\\d+) Watch Later videos in order was written before anything was sent$")
    fun aLocalBackupWasWritten(count: Int) {
        val path = SubfeedStore.state(target).backupFile
        assertNotNull(path)
        val file = File(path!!)
        assertEquals(target.getExternalFilesDir(null)!!.absolutePath, file.parentFile!!.absolutePath)
        assertTrue(file.name.startsWith("subfeed-backup-"))
        val backup = Json.parseToJsonElement(file.readText()).jsonObject
        val wl = backup["watch_later"]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(count, wl.size)
        assertEquals(before, wl)
        assertTrue(backup["history"]!!.jsonArray.size >= finished.size)
        assertEquals(3, backup["subscriptions"]!!.jsonArray.size)
        //The first request the server ever saw is the snapshot - so nothing was sent before the backup
        assertEquals("POST /api/v1/sync/snapshot", state()["requests"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Then("^the server got a snapshot with the (\\d+) Watch Later videos in order$")
    fun theServerGotASnapshot(count: Int) {
        val wl = urls(state()["snapshot_watch_later"])
        assertEquals(count, wl.size)
        assertEquals(before, wl)
    }

    @Then("the server's queue is my Watch Later, order included")
    fun theServersQueueIsMyWatchLater() {
        val s = state()
        val import = s["imports"]!!.jsonArray.last().jsonObject
        assertEquals("true", import["final"]!!.jsonPrimitive.content)
        assertEquals(before, urls(import["items"]))
        assertEquals(before, urls(s["queue"]))
    }

    @Then("my Watch Later is exactly what it was before, order included")
    fun myWatchLaterIsUnchanged() {
        Thread.sleep(2000)
        val now = watchLater()
        assertEquals("Watch Later size", before.size, now.size)
        assertEquals(before, now)
        NavUi.openWatchLater()
        shot("watch-later-" + System.currentTimeMillis())
    }

    @Then("^the server got (\\d+) subscriptions and the history$")
    fun theServerGotSubscriptionsAndHistory(count: Int) {
        val s = state()
        assertEquals(count, s["subscriptions"]!!.jsonArray.size)
        val history = s["history"]!!.jsonArray.map { it.jsonObject }
        for (url in finished) {
            val entry = history.firstOrNull { it["url"]!!.jsonPrimitive.content == url }
            assertNotNull("history for $url", entry)
            assertEquals(60L, entry!!["position_s"]!!.jsonPrimitive.longOrNull)
        }
    }

    @Then("subfeed reports a refused bulk change")
    fun subfeedReportsARefusal() {
        assertNotNull(SubfeedStore.state(target).lastRefusal)
        //The server still has its version; the phone did not adopt it
        assertEquals(expectedAfterPull, urls(state()["queue"]))
    }

    @Then("^my Watch Later lost exactly those (\\d+) videos and kept the order of the rest$")
    fun myWatchLaterLostExactly(count: Int) {
        Wait.until("the pull to be applied") { assertEquals(expectedAfterPull, watchLater()) }
        assertEquals(before.size - count, watchLater().size)
        NavUi.openWatchLater()
        shot("03-watch-later-after-single-removal")
    }

    @Then("my Watch Later is in the server's order")
    fun myWatchLaterIsInTheServersOrder() {
        Wait.until("the pull to be applied") { assertEquals(expectedAfterPull, watchLater()) }
        NavUi.openWatchLater()
        shot("04-watch-later-reversed-by-server")
    }

    @Then("^the server's queue follows to the same (\\d+) videos$")
    fun theServersQueueFollowsToTheSame(count: Int) {
        Wait.until("the server's queue to follow", 60_000) { assertEquals(expectedAfterPull, urls(state()["queue"])) }
        assertEquals(count, expectedAfterPull.size)
    }

    @Then("the server's queue follows to my Watch Later")
    fun theServersQueueFollowsToMyWatchLater() {
        Wait.until("the server's queue to follow", 60_000) { assertEquals(watchLater(), urls(state()["queue"])) }
    }

    @Then("^the server knows about the subscription to \"([^\"]+)\" and that \"([^\"]+)\" is gone$")
    fun theServerKnowsAboutTheSubscriptions(added: String, removed: String) {
        Wait.until("the subscription changes to arrive", 60_000) {
            val subs = urls(state()["subscriptions"])
            assertTrue(subs.toString(), channel(added).url in subs)
            assertTrue(subs.toString(), channel(removed).url !in subs)
        }
    }

    @Then("^the server got the position I paused \"([^\"]+)\" at$")
    fun theServerGotThePausedPosition(name: String) {
        val url = Backdoor.video(name).url
        Wait.until("the paused position to arrive", 60_000) {
            val positions = state()["history"]!!.jsonArray.map { it.jsonObject }.filter { it["url"]!!.jsonPrimitive.content == url }
                .map { it["position_s"]!!.jsonPrimitive.longOrNull ?: -1 }
            assertTrue("positions for $url: $positions, paused at $pausedAt", positions.isNotEmpty() && positions.last() == pausedAt)
        }
    }
    //endregion

    companion object {
        const val SERVER = "http://127.0.0.1:18765"
    }
}
