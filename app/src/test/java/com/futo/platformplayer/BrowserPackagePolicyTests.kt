package com.futo.platformplayer

import com.futo.platformplayer.engine.BrowserPackagePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPackagePolicyTests {
    private fun allowed(debug: Boolean = false, official: Boolean = false, developer: Boolean = false,
                        personal: Boolean = false, bundled: Boolean = false) =
        BrowserPackagePolicy.isAllowed(debug, official, developer, personal, bundled)

    @Test
    fun officialPluginsAreAllowedEverywhere() {
        assertTrue(allowed(official = true))
        assertTrue(allowed(official = true, personal = true))
    }

    @Test
    fun debugBuildsAllowEverything() {
        assertTrue(allowed(debug = true))
    }

    @Test
    fun theDeveloperPluginIsAllowed() {
        assertTrue(allowed(developer = true))
    }

    @Test
    fun unofficialPluginsAreRefusedInReleaseBuilds() {
        assertFalse(allowed())
    }

    //Builds from source can't sign their bundled plugins the way FUTO's release pipeline does
    @Test
    fun personalBuildsTrustThePluginScriptsTheyWereBuiltWith() {
        assertTrue(allowed(personal = true, bundled = true))
    }

    @Test
    fun personalBuildsStillRefuseScriptsThatAreNotBundled() {
        assertFalse(allowed(personal = true, bundled = false))
    }

    @Test
    fun onlyPersonalBuildsTrustUnsignedBundledScripts() {
        assertFalse(allowed(personal = false, bundled = true))
    }
}
