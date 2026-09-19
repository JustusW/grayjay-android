package com.futo.platformplayer.e2e.support

import android.Manifest
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.futo.platformplayer.Settings
import com.futo.platformplayer.stores.FragmentedStorage

/**
 * Puts a freshly wiped app into a state where it starts straight onto a usable Home tab:
 * no first-boot tutorial prompt, no notification permission dialog, no update checks, no casting discovery.
 * Runs in the app process before MainActivity is launched.
 */
object AppSeed {
    fun apply(target: Context) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(target.packageName, Manifest.permission.POST_NOTIFICATIONS)

        target.getSharedPreferences("GrayjayFirstBoot", Context.MODE_PRIVATE).edit()
            .putBoolean("IsFirstBoot", false)
            .commit()

        FragmentedStorage.initialize(target.filesDir)
        Settings.instance.apply {
            didFirstStart = true
            autoUpdate.check = 1 //Never
            autoUpdate.shouldBackgroundDownload = false
            backup.didAskAutoBackup = true
            casting.enabled = false
            //Feed previews autoplay on a second player; keep the only playing video the one under test
            home.previewFeedItems = false
            search.previewFeedItems = false
            subscriptions.previewFeedItems = false
            //Show recommendations below the player right away instead of the comments tab
            comments.recommendationsDefault = true
            //Removing from the queue is under test, the confirmation dialog in front of it is not
            other.playlistDeleteConfirmation = false
        }.saveBlocking()
    }
}
