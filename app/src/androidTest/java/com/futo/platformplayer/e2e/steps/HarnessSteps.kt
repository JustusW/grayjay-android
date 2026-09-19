package com.futo.platformplayer.e2e.steps

import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.e2e.support.onFirst
import io.cucumber.java.en.Then
import org.hamcrest.Matchers.allOf

class HarnessSteps {
    @Then("the home feed shows {string}")
    fun theHomeFeedShows(title: String) {
        Wait.until("home feed item '$title'", timeoutMs = 30_000) {
            onFirst(allOf(withText(title), isDisplayed())).check(matches(isDisplayed()))
        }
    }
}
