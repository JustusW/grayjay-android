package com.futo.platformplayer.e2e.steps

import com.futo.platformplayer.R
import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.PlayerControl
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.e2e.support.clickPartlyVisible
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlayer
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.Assert.assertTrue

class PlaybackSteps {
    @When("I switch to fullscreen")
    fun iSwitchToFullscreen() {
        PlayerControl.waitUntilLoaded()
        PlayerUi.pressControl(R.id.button_fullscreen) { clickPartlyVisible() }
        Wait.until("the player to be fullscreen") { check(PlayerUi.isFullscreen()) }
    }

    @When("I leave fullscreen")
    fun iLeaveFullscreen() {
        PlayerUi.pressControl(R.id.button_fullscreen) { clickPartlyVisible() }
        Wait.until("the player to leave fullscreen") { check(!PlayerUi.isFullscreen()) }
    }

    @Then("the playback time keeps advancing")
    fun thePlaybackTimeKeepsAdvancing() {
        PlayerControl.waitUntilLoaded()
        val before = PlayerUi.shownTimeSeconds()
        val actualBefore = PlayerControl.actual()
        Thread.sleep(3500)
        val after = PlayerUi.shownTimeSeconds()
        val actualAfter = PlayerControl.actual()
        //Only the display is under test: the player itself must actually have played meanwhile
        assertTrue("the player itself didn't play (position ${actualBefore.first}ms -> ${actualAfter.first}ms, playing=${actualAfter.second})",
            actualAfter.first > actualBefore.first + 2000)
        assertTrue("the shown playback time is stuck at ${before}s (still ${after}s 3.5s later) while the player went " +
                "from ${actualBefore.first / 1000}s to ${actualAfter.first / 1000}s", after > before)
    }

    //The resume position is saved at most every 5 seconds, so it is sampled further apart
    @Then("the resume position keeps advancing")
    fun theResumePositionKeepsAdvancing() {
        val url = Backdoor.onMain { StatePlayer.instance.currentVideo?.url }!!
        Thread.sleep(6000)
        val before = StateHistory.instance.getHistoryPosition(url)
        Thread.sleep(6500)
        val after = StateHistory.instance.getHistoryPosition(url)
        assertTrue("the resume position is stuck at ${before}s (still ${after}s 6.5s later)", after > before)
    }
}
