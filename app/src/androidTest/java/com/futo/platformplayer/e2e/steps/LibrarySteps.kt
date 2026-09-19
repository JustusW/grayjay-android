package com.futo.platformplayer.e2e.steps

import com.futo.platformplayer.R
import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.NavUi
import com.futo.platformplayer.e2e.support.PlayerControl
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.states.StatePlayer
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class LibrarySteps {
    private val shuffleStarts = mutableListOf<String>()

    @Given("I play my Watch Later")
    fun iPlayMyWatchLater() {
        NavUi.openWatchLater()
        NavUi.pressListEditorButton(R.id.button_play_all)
        waitForAnythingPlaying()
    }

    @When("^I tap \"([^\"]+)\" in my Watch Later$")
    fun iTapInMyWatchLater(name: String) {
        NavUi.openWatchLater()
        NavUi.tapInListEditor(name)
    }

    @Given("^I play the playlist \"([^\"]+)\"$")
    fun iPlayThePlaylist(name: String) {
        NavUi.openPlaylist(name)
        NavUi.pressListEditorButton(R.id.button_play_all)
        waitForAnythingPlaying()
    }

    @When("I resume the last queue")
    fun iResumeTheLastQueue() {
        NavUi.openPlaylist("Last Queue")
        NavUi.pressListEditorButton("Resume")
        waitForAnythingPlaying()
    }

    @When("^I shuffle my Watch Later (\\d+) times$")
    fun iShuffleMyWatchLaterTimes(times: Int) {
        repeat(times) {
            NavUi.openWatchLater()
            NavUi.pressListEditorButton(R.id.button_shuffle)
            shuffleStarts += waitForAnythingPlaying()
            PlayerUi.close()
        }
    }

    @Then("not every shuffle started with the same video")
    fun notEveryShuffleStartedWithTheSameVideo() {
        assertTrue("every shuffle started with ${shuffleStarts.distinct()}", shuffleStarts.distinct().size > 1)
    }

    private fun waitForAnythingPlaying(): String {
        val name = Wait.until("a video to start playing") {
            Backdoor.onMain { StatePlayer.instance.currentVideo?.name }.also { assertNotNull(it) }!!
        }
        PlayerUi.waitUntilPlaying(name)
        PlayerControl.waitUntilLoaded()
        return name
    }
}
