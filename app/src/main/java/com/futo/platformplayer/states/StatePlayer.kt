package com.futo.platformplayer.states

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.api.media.models.video.IPlatformVideo
import com.futo.platformplayer.api.media.models.video.IPlatformVideoDetails
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import com.futo.platformplayer.constructs.Event0
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.Playlist
import com.futo.platformplayer.queue.PlayQueue
import com.futo.platformplayer.services.MediaPlaybackService
import com.futo.platformplayer.video.PlayerManager
import com.google.common.collect.Iterables


/***
 * Used to keep track of queue and other player related stuff
 */
class StatePlayer {
    private val MIN_BUFFER_DURATION = 10000;
    private val MAX_BUFFER_DURATION = 60000;
    private val MIN_PLAYBACK_START_BUFFER = 500;
    private val MIN_PLAYBACK_RESUME_BUFFER = 2500;
    private val BUFFER_SIZE = 1024 * 64;

    var isOpen : Boolean = false
        private set;

    //Players
    private var _exoplayer : PlayerManager? = null;
    private var _thumbnailExoPlayer : PlayerManager? = null;
    private var _shortExoPlayer: PlayerManager? = null

    //Video Status
    var rotationLock: Boolean = false
        get() = field
        set(value) {
            field = value
            onRotationLockChanged.emit(value)
        }
    val onRotationLockChanged = Event1<Boolean>()
    var autoplay: Boolean = Settings.instance.playback.autoplay
        get() = field
        set(value) {
            if (field != value)
                _autoplayed.clear()
            field = value
            autoplayChanged.emit(value)
        }
    private val _autoplayed = hashSetOf<String>()
    fun wasAutoplayed(url: String?): Boolean {
        if (url == null) {
            return false
        }
        synchronized(_autoplayed) {
            return _autoplayed.contains(url)
        }
    }
    fun setAutoplayed(url: String?) {
        if (url == null) {
            return
        }
        synchronized(_autoplayed) {
            _autoplayed.add(url)
        }
    }

    val autoplayChanged = Event1<Boolean>()
    var loopVideo : Boolean = false;

    val isPlaying: Boolean get() = _exoplayer?.player?.playWhenReady ?: false;

    //Queue
    //The queue order, which item is current, shuffle and repeat live in PlayQueue; this class adds the app's
    //queue semantics (types, Watch Later consumption, parking) and the events the UI listens to.
    private val _queue = PlayQueue<IPlatformVideo>({ it.url });
    private var _queueType = TYPE_QUEUE;
    private var _queueName: String? = null;
    private var _queueRemoveOnFinish = false;
    var queueFocused : Boolean = false
        private set;
    val queueRepeat: Boolean get() = synchronized(_queue) { _queue.repeat };
    val queueShuffle: Boolean get() = synchronized(_queue) { _queue.isShuffled };

    val queueSize: Int get() {
        synchronized(_queue) {
            return _queue.size
        }
    }

    /** A queue exists once something was queued or a list was played; a single tapped video has none. */
    val hasQueue: Boolean get() {
        return queueSize > 0
    }

    val queueName: String get() = _queueName ?: _queueType;

    //Events
    val onVideoChanging = Event1<IPlatformVideo>();
    val onQueueChanged = Event1<Boolean>();
    val onPlayerOpened = Event0();
    val onPlayerClosed = Event0();

    var currentVideo: IPlatformVideo? = null
        private set;

    private var _currentPlaylistId: String? = null
    val playlistId: String? get() = if (_queueType == TYPE_PLAYLIST) _currentPlaylistId else null

    init {
        onQueueChanged.subscribe {
            updateLastQueue()
        }
    }

    fun setCurrentlyPlaying(video: IPlatformVideo?) {
        Log.i(TAG, "setCurrentlyPlaying ${video?.url} (${video?.name})")
        currentVideo = video;
    }


    //Player Status
    fun setPlayerOpen() {
        isOpen = true;
        onPlayerOpened.emit();
    }
    fun setPlayerClosed() {
        Log.i(TAG, "setCurrentlyPlaying (setPlayerClosed) null")
        setCurrentlyPlaying(null);
        isOpen = false;
        clearQueue();
        onPlayerClosed.emit();
        closeMediaSession();
    }

    fun saveQueueAsPlaylist(name: String){
        val videos = getQueue();
        val playlist = Playlist(name, videos.map { SerializedPlatformVideo.fromVideo(it) });
        StatePlaylists.instance.createOrUpdatePlaylist(playlist);
    }

    //Notifications
    fun hasMediaSession() : Boolean {
        return MediaPlaybackService.getService() != null;
    }
    fun startOrUpdateMediaSession(context: Context, videoUpdated: IPlatformVideoDetails?) {
        MediaPlaybackService.getOrCreateService(context) {
            it.updateMediaSession(videoUpdated);
        };
    }
    fun updateMediaSession(videoUpdated: IPlatformVideoDetails?) {
        MediaPlaybackService.getService()?.updateMediaSession(videoUpdated);
    }
    fun updateMediaSessionPlaybackState(state: Int, pos: Long) {
        MediaPlaybackService.getService()?.updateMediaSessionPlaybackState(state, pos);
    }
    fun closeMediaSession() {
        MediaPlaybackService.getService()?.closeMediaSession();
    }

    //Queue Status
    fun getQueueProgress(): Int {
        synchronized(_queue) {
            return _queue.currentIndex;
        }
    }
    fun getQueueLength() : Int {
        synchronized(_queue) {
            return _queue.size;
        }
    }
    fun isInQueue(id : String) : Boolean {
        synchronized(_queue) {
            return _queue.items.any { it.id.value == id };
        }
    }

    fun isUrlInQueue(url : String) : Boolean {
        synchronized(_queue) {
            return _queue.items.any { it.url == url };
        }
    }

    fun getQueueType() : String {
        return _queueType;
    }
    /** The queue in play order (the shuffled order while shuffled). */
    fun getQueue() : List<IPlatformVideo> {
        synchronized(_queue) {
            return _queue.items;
        }
    }

    fun setQueueType(queueType : String) {
        when(queueType) {
            TYPE_QUEUE -> {
                _queueRemoveOnFinish = false;
            }
            TYPE_WATCHLATER -> {
                _queueRemoveOnFinish = true;
            }
            TYPE_PLAYLIST -> {
                _queueRemoveOnFinish = false;
            }
        }
        _queueType = queueType;
    }

    fun setQueueRepeat(enabled: Boolean) {
        synchronized(_queue) {
            _queue.repeat = enabled;
        }
    }
    fun setQueueShuffle(shuffle: Boolean) {
        synchronized(_queue) {
            _queue.setShuffle(shuffle);
        }
        onQueueChanged.emit(false);
    }

    //Modify Queue
    //Every way of starting a new queue starts from scratch: no shuffle, repeat, name or playlist carried over.
    private fun startQueue(videos: List<IPlatformVideo>, type: String, queueName: String?, startAt: Int, focus: Boolean, shuffle: Boolean) {
        synchronized(_queue) {
            setQueueType(type);
            _queueName = queueName;
            _currentPlaylistId = null;
            _queue.set(videos, startAt, shuffle);
            queueFocused = focus;
        }
    }
    fun setQueue(videos: List<IPlatformVideo>, type: String, queueName: String? = null, focus: Boolean = false, shuffle: Boolean = false) {
        startQueue(videos, type, queueName, 0, focus, shuffle);
        onQueueChanged.emit(true);
    }
    fun setPlaylist(playlist: Playlist, toPlayIndex: Int = 0, focus: Boolean = false, shuffle: Boolean = false) {
        startQueue(playlist.videos, TYPE_PLAYLIST, playlist.name, toPlayIndex, focus, shuffle);
        _currentPlaylistId = playlist.id
        StatePlaylists.instance.didPlay(playlist.id);

        onQueueChanged.emit(true);
    }
    fun setQueueWithPosition(videos: List<IPlatformVideo>, type: String, pos: Int, focus: Boolean = false) {
        //TODO: Implement support for pagination
        startQueue(videos, type, null, if (pos in videos.indices) pos else 0, focus, false);
        onQueueChanged.emit(true);
    }
    /** Applies an order chosen in the queue editor; items that left the queue meanwhile are not brought back. */
    fun setQueueWithExisting(videos: List<IPlatformVideo>, withFocus: Boolean = false) {
        synchronized(_queue) {
            _queue.reorder(videos);
        }
        onQueueChanged.emit(false);
    }

    /** Starts a queue behind the video that is playing, if there is no queue yet. Call while holding the lock. */
    private fun ensureQueueStarted() {
        if (!_queue.isEmpty)
            return;
        setQueueType(TYPE_QUEUE);
        _queueName = null;
        _currentPlaylistId = null;
        _queue.set(listOfNotNull(currentVideo));
    }

    fun addToQueue(video: IPlatformVideo) {
        var didAdd = false;
        synchronized(_queue) {
            if(_queue.contains(video)) {
                return@synchronized;
            }

            ensureQueueStarted();
            didAdd = _queue.addLast(video);
        }
        if(didAdd) {
            onQueueChanged.emit(true);
            StateApp.instance.contextOrNull?.let { context ->
                val name = if (video.name.length > 20) (video.name.subSequence(0, 20).toString() + "...") else video.name;
                UIDialogs.toast(context, context.getString(R.string.queued) + " [$name]", false);
            }
        }
        else
            StateApp.instance.contextOrNull?.let { context ->
                UIDialogs.toast(context, context.getString(R.string.already_queued), false);
            }
    }
    /** Places a video directly after the current one (moving it there if already queued). */
    fun addNextToQueue(video: IPlatformVideo) {
        synchronized(_queue) {
            ensureQueueStarted();
            _queue.addNext(video);
        }
        onQueueChanged.emit(true);
    }
    /**
     * What tapping a video does while a queue exists: it plays right away, slotted in after the video that was
     * playing, and the queue carries on from there. An already queued video is jumped to instead of duplicated.
     */
    fun playNow(video: IPlatformVideo) {
        synchronized(_queue) {
            ensureQueueStarted();
            _queue.playNow(video);
        }
        onQueueChanged.emit(true);
    }
    fun moveToFirstInQueue(video: IPlatformVideo) {
        synchronized(_queue) {
            _queue.moveToFirst(video);
        }
        onQueueChanged.emit(false);
    }
    fun moveToLastInQueue(video: IPlatformVideo) {
        synchronized(_queue) {
            _queue.moveToLast(video);
        }
        onQueueChanged.emit(false);
    }

    fun setQueuePosition(video: IPlatformVideo) {
          synchronized(_queue) {
              if (_queue.current?.url == video.url) {
                  return;
              }
              if (!_queue.jumpTo(video)) {
                  return;
              }
          }
          onVideoChanging.emit(video);
    }
    fun getQueuePosition(video: IPlatformVideo): Int {
        synchronized(_queue) {
            return _queue.indexOf(video);
        }
    }
    /** Removing the playing video keeps it playing; the video after it stays next. */
    fun removeFromQueue(video: IPlatformVideo, shouldSwapCurrentItem: Boolean = false) {
        synchronized(_queue) {
            _queue.remove(video);
        }

        onQueueChanged.emit(shouldSwapCurrentItem);
    }
    fun clearQueue() {
        synchronized(_queue) {
            _queue.clear();
            _queueName = null;
            _currentPlaylistId = null;
            setQueueType(TYPE_QUEUE);
        }
        onQueueChanged.emit(false);
    }

    fun updateLastQueue() {
        val queueVideos = synchronized(_queue) {
            if (!_queue.isEmpty) {
                return@synchronized _queue.items.map { SerializedPlatformVideo.fromVideo(it) }.toList()
            }

            return@synchronized null
        }

        if (queueVideos != null) {
            Logger.i(TAG, "Update last queue: ${queueVideos.size} videos.")
            val playlist = StatePlaylists.instance.getPlaylist(StatePlaylists.LAST_QUEUE_PLAYLIST_ID)?.apply {
                videos.clear()
                videos.addAll(queueVideos)
            } ?: Playlist("Last Queue", queueVideos).apply {
                id = StatePlaylists.LAST_QUEUE_PLAYLIST_ID
            }
            StatePlaylists.instance.createOrUpdatePlaylist(playlist)
        }
    }

    //Queue Nav
    fun getCurrentQueueItem(adjustIfNegative: Boolean = true) : IPlatformVideo? {
        synchronized(_queue) {
            return _queue.current ?: if (adjustIfNegative) _queue.items.firstOrNull() else null;
        }
    }

    /***
     * Checks what the prev queue item would without consuming it.
     * @param forceLoop If start of queue should be ignored and loop around to end without queueRepeat being true
     */
    fun getPrevQueueItem(forceLoop: Boolean = false) : IPlatformVideo? {
        synchronized(_queue) {
            return _queue.peekPrevious(forceLoop || _queue.repeat);
        }
    }
    /***
     * Checks what the next queue item would without consuming it.
     * @param forceLoop If end of queue should be ignored and loop around to start without queueRepeat being true
     */
    fun getNextQueueItem(forceLoop: Boolean = false) : IPlatformVideo? {
        synchronized(_queue) {
            return _queue.peekNext(forceLoop || _queue.repeat);
        }
    }
    fun restartQueue() : IPlatformVideo? {
        val first = synchronized(_queue) {
            val first = _queue.items.firstOrNull() ?: return null;
            _queue.jumpTo(first);
            first
        }
        return first;
    }

    //The queue's current item is only consumed when it is what actually played (not e.g. a video opened by url)
    private fun shouldConsumeCurrent(withoutRemoval: Boolean): Boolean =
        _queueRemoveOnFinish && !withoutRemoval && _queue.current?.url == currentVideo?.url;

    /***
     * Triggers the next queue item, removing it depending on the queue type, should ONLY be used if you're directly consuming this item
     * @param withoutRemoval Prevents the removal behavior of certain playlists, should be true for manual user actions like next
     * @param bypassVideoLoop Bypasses any single-video-looping behavior, should be true for manual user actions like next
     */
    fun nextQueueItem(withoutRemoval: Boolean = false, bypassVideoLoop: Boolean = false) : IPlatformVideo? {
        if(loopVideo && !bypassVideoLoop) {
            return currentVideo;
        }

        val next = synchronized(_queue) {
            if (_queue.isEmpty) {
                return null;
            }
            _queue.advance(_queue.repeat, shouldConsumeCurrent(withoutRemoval))
        }
        return next;
    }

    /***
     * Triggers the prev queue item, removing it depending on the queue type
     * @param withoutRemoval Prevents the removal behavior of certain playlists, should be true for manual user actions like next
     */
    fun prevQueueItem(withoutRemoval: Boolean = false) : IPlatformVideo? {
        val previous = synchronized(_queue) {
            if (_queue.isEmpty) {
                return null;
            }
            _queue.goBack(true, shouldConsumeCurrent(withoutRemoval))
        }
        return previous;
    }

    //Player Initialization
    fun getPlayerOrCreate(context: Context) : PlayerManager {
        if(_exoplayer == null) {
            val player = createExoPlayer(context);
            _exoplayer = PlayerManager(player);
        }
        return _exoplayer!!;
    }
    fun getThumbnailPlayerOrCreate(context: Context) : PlayerManager {
        if(_thumbnailExoPlayer == null) {
            val player = createExoPlayer(context);
            _thumbnailExoPlayer = PlayerManager(player);
        }
        return _thumbnailExoPlayer!!;
    }
    fun getShortPlayerOrCreate(context: Context) : PlayerManager {
        if(_shortExoPlayer == null) {
            val player = createExoPlayer(context);
            _shortExoPlayer = PlayerManager(player);
        }
        return _shortExoPlayer!!;
    }

    @OptIn(UnstableApi::class)
    private fun createExoPlayer(context : Context): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setRenderersFactory(
                object : DefaultRenderersFactory(context) {
                    override fun buildTextRenderers(
                        context: Context,
                        output: TextOutput,
                        outputLooper: Looper,
                        extensionRendererMode: Int,
                        out: java.util.ArrayList<Renderer>
                    ) {
                        super.buildTextRenderers(
                            context,
                            output,
                            outputLooper,
                            extensionRendererMode,
                            out
                        )
                        (Iterables.getLast<Renderer?>(out) as TextRenderer)
                            .experimentalSetLegacyDecodingEnabled(true)
                    }
                })
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .experimentalParseSubtitlesDuringExtraction(false)
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setAllocator(DefaultAllocator(true, BUFFER_SIZE))
                    .setBufferDurationsMs(
                        MIN_BUFFER_DURATION,
                        MAX_BUFFER_DURATION,
                        MIN_PLAYBACK_START_BUFFER,
                        MIN_PLAYBACK_RESUME_BUFFER
                    )
                    .setTargetBufferBytes(-1)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build())
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build();
    }

    fun dispose(){
        val player = _exoplayer;
        val thumbPlayer = _thumbnailExoPlayer;
        val shortPlayer = _shortExoPlayer
        _exoplayer = null;
        _thumbnailExoPlayer = null;
        _shortExoPlayer = null
        player?.release();
        thumbPlayer?.release();
        shortPlayer?.release()
    }


    companion object {
        val TAG = "PlayerState";
        val TYPE_QUEUE = "Queue";
        val TYPE_PLAYLIST = "Playlist";
        val TYPE_WATCHLATER = "Watch Later";

        private var _instance : StatePlayer? = null;
        val instance : StatePlayer
            get(){
            if(_instance == null)
                _instance = StatePlayer();
            return _instance!!;
        };

        fun dispose(){
            val instance = _instance;
            _instance = null;
            instance?.dispose();
            Logger.i(TAG, "Disposed StatePlayer");
        }
    }
}