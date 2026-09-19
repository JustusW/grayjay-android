package com.futo.platformplayer.engine

/**
 * Who may use the Browser package, which gives a plugin a real web browser and so a lot of power.
 *
 * Official plugins qualify through FUTO's signature. FUTO's release pipeline signs the plugins it bundles into the
 * app, but a build from source (a personal build) can't, so it ships them unsigned; a personal build therefore
 * trusts exactly the plugin scripts it was built with, and nothing else that is unsigned.
 */
object BrowserPackagePolicy {
    fun isAllowed(isDebugBuild: Boolean, isOfficialPlugin: Boolean, isDeveloperPlugin: Boolean,
                  isPersonalBuild: Boolean, isBundledScript: Boolean): Boolean =
        isDebugBuild || isOfficialPlugin || isDeveloperPlugin || (isPersonalBuild && isBundledScript)
}
