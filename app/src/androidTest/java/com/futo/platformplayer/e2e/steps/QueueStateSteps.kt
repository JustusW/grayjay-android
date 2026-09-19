package com.futo.platformplayer.e2e.steps

import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.e2e.support.quotedNames
import com.futo.platformplayer.models.Playlist
import com.futo.platformplayer.states.StatePlayer
import com.futo.platformplayer.states.StatePlaylists
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Arranging and asserting queue/library state. Assertions poll, because the app updates state asynchronously. */
class QueueStateSteps {

    @Given("^I am playing a queue of ((?:\"[^\"]+\"(?:, )?)+?)(?: starting at \"([^\"]+)\")?$")
    fun iAmPlayingAQueueOf(list: String, startAt: String?) {
        val videos = quotedNames(list).map { Backdoor.video(it) }
        val index = startAt?.let { start -> videos.indexOfFirst { it.name == start } } ?: 0
        Backdoor.onMain { StatePlayer.instance.setQueueWithPosition(videos, StatePlayer.TYPE_QUEUE, index, true) }
        PlayerUi.waitUntilPlaying(videos[index].name)
    }

    @Given("^my Watch Later contains ((?:\"[^\"]+\"(?:, )?)+)$")
    fun myWatchLaterContains(list: String) {
        StatePlaylists.instance.updateWatchLater(ArrayList(quotedNames(list).map { Backdoor.video(it) }))
    }

    @Then("^my Watch Later still contains ((?:\"[^\"]+\"(?:, )?)+)$")
    fun myWatchLaterStillContains(list: String) {
        val names = quotedNames(list)
        //Removal happens asynchronously after a video ends; give it time to (wrongly) happen before asserting
        Thread.sleep(1500)
        val current = StatePlaylists.instance.getWatchLater().map { it.name }
        assertTrue("Watch Later is $current, expected it to contain $names", current.containsAll(names))
    }

    @Given("^I have a playlist \"([^\"]+)\" with ((?:\"[^\"]+\"(?:, )?)+)$")
    fun iHaveAPlaylist(name: String, list: String) {
        StatePlaylists.instance.createOrUpdatePlaylist(Playlist(name, quotedNames(list).map { Backdoor.video(it) }), false)
    }

    @Then("^the queue is ((?:\"[^\"]+\"(?:, )?)+)$")
    fun theQueueIs(list: String) {
        val expected = quotedNames(list)
        Wait.until("queue to be $expected") {
            assertEquals(expected, StatePlayer.instance.getQueue().map { it.name })
        }
    }

    @Then("^the queue contains exactly ((?:\"[^\"]+\"(?:, )?)+)$")
    fun theQueueContainsExactly(list: String) {
        val expected = quotedNames(list).sorted()
        Wait.until("queue to contain exactly $expected") {
            assertEquals(expected, StatePlayer.instance.getQueue().map { it.name }.sorted())
        }
    }

    @Then("there is no queue")
    fun thereIsNoQueue() {
        //Give any queue handling a moment to happen before asserting its absence
        Thread.sleep(1000)
        assertTrue("queue: ${StatePlayer.instance.getQueue().map { it.name }}", StatePlayer.instance.getQueue().size <= 1)
    }

    @Then("^my Watch Later is ((?:\"[^\"]+\"(?:, )?)+)$")
    fun myWatchLaterIs(list: String) {
        val expected = quotedNames(list)
        Wait.until("Watch Later to be $expected") {
            assertEquals(expected, StatePlaylists.instance.getWatchLater().map { it.name })
        }
    }

    @Then("^the last queue is ((?:\"[^\"]+\"(?:, )?)+)$")
    fun theLastQueueIs(list: String) {
        val expected = quotedNames(list)
        Wait.until("Last Queue to be $expected") {
            val playlist = StatePlaylists.instance.getPlaylist(StatePlaylists.LAST_QUEUE_PLAYLIST_ID)
            assertEquals(expected, playlist?.videos?.map { it.name })
        }
    }

    @Then("repeat is off")
    fun repeatIsOff() {
        Wait.until("repeat to be off") { assertFalse("repeat is on", StatePlayer.instance.queueRepeat) }
    }
}
