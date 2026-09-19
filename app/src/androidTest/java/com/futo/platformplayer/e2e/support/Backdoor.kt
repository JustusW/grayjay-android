package com.futo.platformplayer.e2e.support

import androidx.test.platform.app.InstrumentationRegistry
import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.models.PlatformAuthorLink
import com.futo.platformplayer.api.media.models.Thumbnail
import com.futo.platformplayer.api.media.models.Thumbnails
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Direct access to app state for arranging scenarios (Given) and for assertions the UI can't express.
 * Actions under test (When) always go through the UI.
 */
object Backdoor {
    private const val PLATFORM = "Fixture"

    /** A fixture video as the app stores it in Watch Later, playlists and the queue. */
    fun video(n: Int): SerializedPlatformVideo {
        val channelKey = if (n <= 6) "alpha" else "beta"
        val channelName = if (n <= 6) "Alpha Channel" else "Beta Channel"
        val thumb = "${Fixture.baseUrl}/thumb.png"
        return SerializedPlatformVideo(
            id = PlatformID(PLATFORM, "v%02d".format(n), Fixture.PLUGIN_ID),
            name = Fixture.videoName(n),
            thumbnails = Thumbnails(arrayOf(Thumbnail(thumb, 180))),
            author = PlatformAuthorLink(PlatformID(PLATFORM, channelKey, Fixture.PLUGIN_ID), channelName,
                "https://fixture.test/channel/$channelKey", thumb, 1000),
            datetime = OffsetDateTime.of(2023, 11, 14, 12, 0, 0, 0, ZoneOffset.UTC).minusHours(n.toLong()),
            url = Fixture.videoUrl(n),
            shareUrl = Fixture.videoUrl(n),
            duration = 60,
            viewCount = 1000L + n
        )
    }

    /** "Video 07" -> the fixture video 7. */
    fun video(name: String): SerializedPlatformVideo = video(numberOf(name))

    fun numberOf(name: String): Int = name.removePrefix("Video ").trim().toInt()

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}

/** Parses step text like `"Video 01", "Video 02"` into the quoted names. */
fun quotedNames(text: String): List<String> = Regex("\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
