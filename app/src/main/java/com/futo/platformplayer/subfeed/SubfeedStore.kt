package com.futo.platformplayer.subfeed

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The bridge's own persistent state, in files/subfeed/ (not in Grayjay's stores, so upstream merges never touch it).
 * The outbox worker can read it without the rest of the app being started.
 */
@Serializable
data class SubfeedState(
    //Mirrored from the settings so the outbox worker works without the app's state loaded
    var enabled: Boolean = false,
    var serverUrl: String = SubfeedSettings.DEFAULT_URL,

    //First sync, in order. Nothing goes to the server before the backup exists, nothing is applied from it before
    //the import is confirmed.
    var backupFile: String? = null,
    var snapshotPosted: Boolean = false,
    var subscriptionsPushed: Boolean = false,
    var historyPushed: Boolean = false,
    var importPosted: Boolean = false,
    var importedUrls: List<String> = listOf(),
    var importConfirmed: Boolean = false,

    //What the server's queue holds as far as this phone knows (after the outbox has drained)
    var syncedQueue: List<String> = listOf(),
    var queueVersion: Long? = null,
    //The server keeps the queue read only (SUBFEED_QUEUE_READ_ONLY): Watch Later goes as a whole import, never as
    //single changes
    var queueReadOnly: Boolean = false,

    var lastSyncAt: Long = 0,
    var lastSnapshotAt: Long = 0,
    //A snapshot holding the playlists has reached the server. False in a state written by a version that didn't send
    //playlists yet: then a backup and a snapshot with them go out before anything else, once.
    var playlistsSnapshotPosted: Boolean = false,
    var playlistsBackupFile: String? = null,
    var lastError: String? = null,
    var lastRefusal: String? = null,
    var refusedVersion: Long? = null,
    var prioritySince: String? = null
) {
    val firstSyncDone: Boolean get() = backupFile != null && snapshotPosted && subscriptionsPushed && historyPushed && importConfirmed

    /** The last snapshot sent had no playlists (an earlier version sent it): one with them is due first. */
    val playlistsSnapshotDue: Boolean get() = snapshotPosted && !playlistsSnapshotPosted
}

object SubfeedStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var _dir: File? = null
    private var _state: SubfeedState? = null
    private var _outbox: Outbox? = null

    @Synchronized
    fun dir(context: Context): File {
        val dir = _dir ?: File(context.applicationContext.filesDir, "subfeed").also { it.mkdirs(); _dir = it }
        return dir
    }

    @Synchronized
    fun outbox(context: Context): Outbox = _outbox ?: Outbox(File(dir(context), "outbox.json")).also { _outbox = it }

    @Synchronized
    fun state(context: Context): SubfeedState {
        _state?.let { return it }
        val file = File(dir(context), "state.json")
        val loaded = try {
            if (file.exists()) json.decodeFromString<SubfeedState>(file.readText()) else SubfeedState()
        } catch (e: Throwable) {
            file.copyTo(File(file.parentFile, "state.json.damaged-" + System.currentTimeMillis()), true)
            SubfeedState()
        }
        _state = loaded
        return loaded
    }

    /** Changes the state under the store's lock and writes it through. */
    @Synchronized
    fun update(context: Context, change: (SubfeedState) -> Unit): SubfeedState {
        val state = state(context)
        change(state)
        val file = File(dir(context), "state.json")
        val tmp = File(dir(context), "state.json.tmp")
        tmp.writeText(json.encodeToString(state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        return state
    }
}
