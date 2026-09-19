package com.futo.platformplayer.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.e2e.support.AppSeed
import com.futo.platformplayer.e2e.support.Fixture
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario

/**
 * Each scenario runs in its own freshly wiped app process (Android Test Orchestrator + clearPackageData),
 * so setup here always starts from zero: seed, install the fixture source, launch.
 */
class Hooks {
    @Before(order = 0)
    fun launchApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext

        AppSeed.apply(target)
        Fixture.startMediaServer(target, instrumentation.context)
        Fixture.installPlugin(instrumentation.context)

        World.activity = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Leaves a screenshot and view dump of a failed scenario in /data/local/tmp/e2e (pull with adb). */
    //After hooks with a higher order run first; this must run before tearDown (default order 10000)
    @After(order = 20000)
    fun captureFailure(scenario: Scenario) {
        if (!scenario.isFailed)
            return
        try {
            val name = scenario.name.replace(Regex("[^A-Za-z0-9]+"), "_").take(80)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            for (command in listOf("mkdir -p /data/local/tmp/e2e",
                "screencap -p /data/local/tmp/e2e/$name.png",
                "uiautomator dump /data/local/tmp/e2e/$name.xml"))
                automation.executeShellCommand(command).close()
            Thread.sleep(1500)
        } catch (_: Throwable) {
        }
    }

    @After
    fun tearDown() {
        //Deliberately no ActivityScenario.close(): MainActivity doesn't reach DESTROYED on request, so close()
        //burns its full 45s timeout. The orchestrator wipes and kills the process before the next scenario anyway.
        World.activity = null
        Fixture.stopMediaServer()
    }
}

/** Per-scenario shared state between step definition classes. */
object World {
    var activity: ActivityScenario<MainActivity>? = null
}
