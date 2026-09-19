package com.futo.platformplayer.e2e.steps

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import android.view.View
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.futo.platformplayer.R
import com.futo.platformplayer.e2e.support.PlayerControl
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.e2e.support.ownVisibility
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

class PlayerSteps {
    @Then("^\"([^\"]+)\" is (?:still )?playing$")
    fun isPlaying(name: String) = PlayerUi.waitUntilPlaying(name)

    @When("the current video finishes")
    fun theCurrentVideoFinishes() = PlayerControl.finishCurrentVideo()

    @When("I skip to the next video")
    fun iSkipToTheNextVideo() {
        PlayerControl.waitUntilLoaded()
        PlayerUi.pressControl(R.id.button_next)
    }

    @When("I skip to the previous video")
    fun iSkipToThePreviousVideo() {
        PlayerControl.waitUntilLoaded()
        PlayerUi.pressControl(R.id.button_previous)
    }

    @When("I close the player")
    fun iCloseThePlayer() = PlayerUi.close()

    @When("^I tap the recommended video \"([^\"]+)\"$")
    fun iTapTheRecommendedVideo(name: String) = PlayerUi.tapRecommended(name)

    @When("I turn repeat on")
    fun iTurnRepeatOn() = PlayerUi.pressUpNextButton(R.id.button_repeat)

    @When("I turn shuffle on")
    fun iTurnShuffleOn() = PlayerUi.pressUpNextButton(R.id.button_shuffle)

    //Up Next may be covered by the queue panel; its own state still says what the app will play next
    @Then("^the next video is \"([^\"]+)\"$")
    fun theNextVideoIs(name: String) {
        Wait.until("up next to show '$name'") {
            onView(withId(R.id.videodetail_queue)).check(matches(ownVisibility(View.VISIBLE)))
            onView(withId(R.id.videodetail_queue_box)).check(matches(ownVisibility(View.VISIBLE)))
            onView(withId(R.id.videodetail_queue_title)).check(matches(withText(name)))
        }
    }

    @Then("the up next section is shown")
    fun theUpNextSectionIsShown() {
        Wait.until("up next to be shown") {
            onView(withId(R.id.videodetail_queue)).check(matches(ownVisibility(View.VISIBLE)))
        }
    }

    @Then("the up next section says the end of the queue is reached")
    fun theEndOfTheQueueIsReached() {
        Wait.until("the end of queue notice") {
            onView(withId(R.id.videodetail_queue)).check(matches(ownVisibility(View.VISIBLE)))
            onView(withId(R.id.videodetail_end_of_playlist)).check(matches(ownVisibility(View.VISIBLE)))
            onView(withId(R.id.text_end_of_queue)).check(matches(withText("End of queue reached")))
        }
    }
}
