package com.futo.platformplayer.e2e

import io.cucumber.junit.CucumberOptions

/**
 * Entry point for the Gherkin end-to-end suite.
 * Features live in src/androidTest/assets/features, step definitions in this package.
 * Narrow a run with e.g. -Pandroid.testInstrumentationRunnerArguments.tags=@queue-panel
 */
@CucumberOptions(
    features = ["features"],
    glue = ["com.futo.platformplayer.e2e"]
)
class CucumberTestOptions
