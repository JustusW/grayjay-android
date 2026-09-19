package com.futo.platformplayer.e2e.steps

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.futo.platformplayer.R
import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.FeedUi
import com.futo.platformplayer.e2e.support.NavUi
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.states.StateHistory
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.hamcrest.Matchers.allOf

class FeedSteps {
    @Given("^I am watching \"([^\"]+)\"$")
    fun iAmWatching(name: String) {
        FeedUi.tap(name)
        PlayerUi.waitUntilPlaying(name)
    }

    @Given("^I watched \"([^\"]+)\" earlier$")
    fun iWatchedEarlier(name: String) {
        StateHistory.instance.markAsWatched(Backdoor.video(name))
    }

    /** Unlike "shows", this scrolls, so the app has to build the item's view. */
    @Then("^the home feed contains \"([^\"]+)\"$")
    fun theHomeFeedContains(name: String) {
        FeedUi.bringIntoView(name)
        onView(allOf(withId(R.id.text_video_name), withText(name), isDisplayed())).check(matches(isDisplayed()))
    }

    @When("^I tap \"([^\"]+)\" in the home feed$")
    fun iTapInTheHomeFeed(name: String) = FeedUi.tap(name)

    @When("^I add \"([^\"]+)\" to the queue from the home feed$")
    fun iAddToTheQueueFromTheHomeFeed(name: String) = FeedUi.addToQueue(name)

    @When("^I tap the play next button of \"([^\"]+)\" in the home feed$")
    fun iTapThePlayNextButtonInTheHomeFeed(name: String) = FeedUi.pressPlayNext(name)

    @When("^I choose \"([^\"]+)\" for \"([^\"]+)\" in the home feed$")
    fun iChooseForInTheHomeFeed(option: String, name: String) = FeedUi.chooseOption(name, option)

    @When("^I open the channel \"([^\"]+)\"$")
    fun iOpenTheChannel(channel: String) = FeedUi.openChannelOf(channel)

    @When("^I tap \"([^\"]+)\" on the channel page$")
    fun iTapOnTheChannelPage(name: String) = FeedUi.tapOnChannelPage(name)

    @When("^I tap \"([^\"]+)\" in my watch history$")
    fun iTapInMyWatchHistory(name: String) = NavUi.tapInHistory(name)
}
