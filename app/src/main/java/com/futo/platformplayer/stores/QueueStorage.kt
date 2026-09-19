package com.futo.platformplayer.stores

import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistence for the play queue.
 * - [liveVideos]/[liveIndex]: the queue while it is playing, so it survives the app being killed.
 * - [parkedIndex]: which video was playing when the queue was parked into the "Last Queue" playlist.
 */
@kotlinx.serialization.Serializable
class QueueStorage : FragmentedStorageFileJson() {
    var liveVideos: List<SerializedPlatformVideo> = listOf()
    var liveIndex: Int = -1
    var parkedIndex: Int = 0

    override fun encode(): String {
        return Json.encodeToString(this)
    }
}
