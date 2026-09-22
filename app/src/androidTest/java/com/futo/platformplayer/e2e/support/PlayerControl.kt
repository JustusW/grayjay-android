package com.futo.platformplayer.e2e.support

import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.futo.platformplayer.states.StatePlayer
import org.junit.Assert.assertTrue

/**
 * Test control over time: fixture videos are 60s long, so "the video finishes" is simulated by seeking to
 * just before its end. What happens *after* the end (the behavior under test) runs through the real code path.
 */
object PlayerControl {
    private val player get() = StatePlayer.instance
        .getPlayerOrCreate(InstrumentationRegistry.getInstrumentation().targetContext).player

    fun currentUrl(): String? = Backdoor.onMain { StatePlayer.instance.currentVideo?.url }

    /** What the player itself is doing, independent of any UI: position in ms and whether it is playing. */
    fun actual(): Pair<Long, Boolean> = Backdoor.onMain { Pair(player.currentPosition, player.isPlaying) }

    fun waitUntilLoaded() {
        Wait.until("the playing video to be loaded") {
            assertTrue(Backdoor.onMain { player.duration > 0 && player.playbackState == Player.STATE_READY })
        }
    }

    fun finishCurrentVideo() {
        waitUntilLoaded()
        val before = currentUrl()
        Backdoor.onMain {
            player.seekTo(player.duration - 400)
            player.play()
        }
        Wait.until("the current video to finish", timeoutMs = 20_000) {
            assertTrue(Backdoor.onMain { StatePlayer.instance.currentVideo?.url != before || player.playbackState == Player.STATE_ENDED })
        }
    }
}
