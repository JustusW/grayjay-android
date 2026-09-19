package com.futo.platformplayer.e2e.steps

import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.FeedUi
import com.futo.platformplayer.e2e.support.NavUi
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.states.StateHistory
import io.cucumber.java.en.Given
import io.cucumber.java.en.When

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

    @When("^I tap \"([^\"]+)\" in the home feed$")
    fun iTapInTheHomeFeed(name: String) = FeedUi.tap(name)

    @When("^I add \"([^\"]+)\" to the queue from the home feed$")
    fun iAddToTheQueueFromTheHomeFeed(name: String) = FeedUi.addToQueue(name)

    @When("^I choose \"([^\"]+)\" for \"([^\"]+)\" in the home feed$")
    fun iChooseForInTheHomeFeed(option: String, name: String) = FeedUi.chooseOption(name, option)

    @When("^I open the channel \"([^\"]+)\"$")
    fun iOpenTheChannel(channel: String) = FeedUi.openChannelOf(channel)

    @When("^I tap \"([^\"]+)\" on the channel page$")
    fun iTapOnTheChannelPage(name: String) = FeedUi.tapOnChannelPage(name)

    @When("^I tap \"([^\"]+)\" in my watch history$")
    fun iTapInMyWatchHistory(name: String) = NavUi.tapInHistory(name)
}
