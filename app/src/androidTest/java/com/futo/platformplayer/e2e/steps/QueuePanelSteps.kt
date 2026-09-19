package com.futo.platformplayer.e2e.steps

import androidx.test.espresso.Espresso.onView
import com.futo.platformplayer.R
import com.futo.platformplayer.e2e.support.PlayerUi
import com.futo.platformplayer.e2e.support.QueuePanelUi
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.e2e.support.hasItemTitles
import com.futo.platformplayer.e2e.support.quotedNames
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.Assert.assertEquals

class QueuePanelSteps {
    @When("I open the queue")
    fun iOpenTheQueue() = QueuePanelUi.open()

    @When("^I choose \"([^\"]+)\" for \"([^\"]+)\" in the queue$")
    fun iChooseForInTheQueue(option: String, name: String) = QueuePanelUi.chooseOption(name, option)

    @When("^I tap \"([^\"]+)\" in the queue$")
    fun iTapInTheQueue(name: String) = QueuePanelUi.tap(name)

    @When("^I remove \"([^\"]+)\" from the queue$")
    fun iRemoveFromTheQueue(name: String) = QueuePanelUi.remove(name)

    @Then("^the queue options for \"([^\"]+)\" are ((?:\"[^\"]+\"(?:, )?)+)$")
    fun theQueueOptionsForAre(name: String, list: String) {
        val expected = quotedNames(list)
        QueuePanelUi.openOptions(name)
        Wait.until("the queue options to be $expected") {
            assertEquals(expected, PlayerUi.menuOptionTexts().filter { it in expected })
        }
    }

    @Then("the queue panel is open")
    fun theQueuePanelIsOpen() {
        //Give a (wrongly) closing panel time to slide away before checking
        Thread.sleep(1000)
        QueuePanelUi.isOpen()
    }

    @Then("^the queue panel lists ((?:\"[^\"]+\"(?:, )?)+)$")
    fun theQueuePanelLists(list: String) {
        val expected = quotedNames(list)
        Wait.until("the queue panel to list $expected") {
            QueuePanelUi.isOpen()
            onView(QueuePanelUi.list).check(hasItemTitles(R.id.text_video_name, expected))
        }
    }

    @Then("^\"([^\"]+)\" is marked as playing in the queue panel$")
    fun isMarkedAsPlayingInTheQueuePanel(name: String) {
        Wait.until("'$name' to be marked as playing in the queue panel") {
            QueuePanelUi.isOpen()
            QueuePanelUi.assertMarkedAsPlaying(name)
        }
    }
}
