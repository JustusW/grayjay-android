package com.futo.platformplayer.e2e.steps

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.e2e.World
import com.futo.platformplayer.e2e.support.App
import com.futo.platformplayer.e2e.support.Backdoor
import com.futo.platformplayer.e2e.support.Wait
import com.futo.platformplayer.states.StatePlayer
import io.cucumber.java.en.When

class AppSteps {
    /**
     * Approximates the app being killed and started again: the activity goes away, everything the player held
     * in memory is dropped, and the app starts from what it persisted. (A real process kill would also end the
     * instrumentation, so it can't be done inside a scenario.)
     */
    @When("the app is restarted")
    fun theAppIsRestarted() {
        val activity = App.activity()
        Backdoor.onMain { activity.finish() }
        Wait.until("the app to go away") {
            check(Backdoor.onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.DESTROYED).contains(activity) || activity.isDestroyed })
        }
        Backdoor.onMain { StatePlayer.dispose() }
        World.activity = ActivityScenario.launch(MainActivity::class.java).also {
            Wait.until("the app to start again") { check(it.state.isAtLeast(Lifecycle.State.RESUMED)) }
        }
    }
}
