package com.maxrave.media3.exoplayer

import android.annotation.SuppressLint
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Crossfade-capable wrapper around ExoPlayerAdapter for Android.
 *
 * Architecture:
 * - The primary ExoPlayer holds the full playlist and is bound to MediaSession.
 * - A secondary ExoPlayer is created on-demand for crossfade transitions.
 * - During crossfade: both play simultaneously with volume fading.
 * - After crossfade: primary player seeks to next track (cached, near-instant),
 *   secondary is disposed. Primary remains the MediaSession player throughout.
 *
 * This class delegates all normal operations to [primaryAdapter] (an [ExoPlayerAdapter]).
 * It only intervenes for crossfade timing, secondary player management, and volume control.
 *
 * Listener pattern:
 * - Maintains its own listener list, mirroring the JVM [GstreamerPlayerAdapter] pattern.
 * - Each listener is also registered with [primaryAdapter] via a wrapper that suppresses
 *   duplicate [onMediaItemTransition] events during crossfade finalization.
 * - When crossfade starts, [onMediaItemTransition] is fired directly on listeners (like JVM).
 * - When [finalizeCrossfade] calls seekTo on the primary ExoPlayer, ExoPlayer fires its own
 *   [onMediaItemTransition] — the wrapper suppresses this to avoid duplicate processing.
 */
private const val TAG = "CrossfadeExoPlayerAdapter"

@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
class CrossfadeExoPlayerAdapter internal constructor(
    private val primaryAdapter: ExoPlayerAdapter,
    private val primaryExoPlayer: ExoPlayer,
    private val exoPlayerFactory: ExoPlayerFactory,
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
) : MediaPlayerInterface {

    // ========== Listener Management ==========

    /**
     * Own listener list — mirrors the JVM pattern where the adapter directly fires events.
     * Each listener here has a corresponding [TransitionSuppressingListener] wrapper
     * registered with [primaryAdapter] to prevent duplicate onMediaItemTransition calls.
     */
    private val listeners = mutableListOf<MediaPlayerListener>()

    /**
     * Map from original listener -> wrapper registered with primaryAdapter.
     * The wrapper suppresses onMediaItemTransition when [suppressNextTransition] is set.
     */
    private val listenerWrappers = mutableMapOf<MediaPlayerListener, TransitionSuppressingListener>()

    /**
     * When true, the next onMediaItemTransition from primaryAdapter (ExoPlayer) will be suppressed.
     * This is set after crossfade fires its own transition, and cleared after finalizeCrossfade
     * completes the seekTo on the primary player (which triggers ExoPlayer's own transition).
     */
    @Volatile
    private var suppressNextTransition = false

    // ========== Crossfade State ==========

    @Volatile
    private var crossfadeEnabled = false

    @Volatile
    private var crossfadeDurationMs = 5000

    @Volatile
    private var isCrossfading = false

    @Volatile
    private var secondaryExoPlayer: ExoPlayer? = null

    @Volatile
    private var crossfadeJob: Job? = null

    private var positionMonitorJob: Job? = null

    /**
     * Callback for the handler to know when crossfade state changes.
     * The handler uses this to update [ControlState.isCrossfading].
     */
    var onCrossfadeStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Expose the primary ExoPlayer for MediaSession binding.
     * The MediaSession should always be bound to this player.
     */
    val sessionPlayer: ExoPlayer get() = primaryExoPlayer

    /**
     * Expose the secondary ExoPlayer's audio session ID for LoudnessEnhancer.
     * Returns null when no secondary player exists.
     */
    val secondaryAudioSessionId: Int?
        get() = secondaryExoPlayer?.audioSessionId

    init {
        // Collect crossfade settings
        coroutineScope.launch {
            dataStoreManager.crossfadeEnabled.collect { enabled ->
                crossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Crossfade enabled: $crossfadeEnabled")
                if (crossfadeEnabled) {
                    startPositionMonitor()
                } else {
                    stopPositionMonitor()
                    cancelCrossfade()
                }
            }
        }

        coroutineScope.launch {
            dataStoreManager.crossfadeDuration.collect { duration ->
                crossfadeDurationMs = duration
                Logger.d(TAG, "Crossfade duration: $crossfadeDurationMs ms")
            }
        }
    }

    /**
     * Wrapper listener that delegates all events to the real listener,
     * but suppresses [onMediaItemTransition] when [suppressNextTransition] is set.
     * This prevents duplicate transition events when finalizeCrossfade triggers
     * ExoPlayer's own onMediaItemTransition via seekTo.
     */
    private inner class TransitionSuppressingListener(
        private val delegate: MediaPlayerListener,
    ) : MediaPlayerListener {
        override fun onPlaybackStateChanged(playbackState: Int) = delegate.onPlaybackStateChanged(playbackState)
        override fun onIsPlayingChanged(isPlaying: Boolean) = delegate.onIsPlayingChanged(isPlaying)
        override fun onMediaItemTransition(mediaItem: GenericMediaItem?, reason: Int) {
            if (suppressNextTransition) {
                Logger.d(TAG, "Suppressed duplicate onMediaItemTransition from ExoPlayer (reason=$reason)")
                suppressNextTransition = false
                return
            }
            delegate.onMediaItemTransition(mediaItem, reason)
        }
        override fun onTimelineChanged(list: List<GenericMediaItem>, reason: String) = delegate.onTimelineChanged(list, reason)
        override fun onTracksChanged(tracks: com.maxrave.domain.data.player.GenericTracks) = delegate.onTracksChanged(tracks)
        override fun onPlayerError(error: com.maxrave.domain.data.player.PlayerError) = delegate.onPlayerError(error)
        override fun shouldOpenOrCloseEqualizerIntent(shouldOpen: Boolean) = delegate.shouldOpenOrCloseEqualizerIntent(shouldOpen)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean, list: List<GenericMediaItem>) = delegate.onShuffleModeEnabledChanged(shuffleModeEnabled, list)
        override fun onRepeatModeChanged(repeatMode: Int) = delegate.onRepeatModeChanged(repeatMode)
        override fun onIsLoadingChanged(isLoading: Boolean) = delegate.onIsLoadingChanged(isLoading)
        override fun onVolumeChanged(volume: Float) = delegate.onVolumeChanged(volume)
    }

    // ========== Position Monitoring for Crossfade Trigger ==========

    private fun startPositionMonitor() {
        stopPositionMonitor()
        positionMonitorJob = coroutineScope.launch {
            while (isActive) {
                try {
                    if (crossfadeEnabled &&
                        !isCrossfading &&
                        primaryExoPlayer.isPlaying &&
                        primaryExoPlayer.duration > 0 &&
                        primaryExoPlayer.currentPosition > 0 &&
                        primaryAdapter.currentMediaItem?.isVideo() != true
                    ) {
                        val timeRemaining = primaryExoPlayer.duration - primaryExoPlayer.currentPosition
                        if (timeRemaining in 1..crossfadeDurationMs.toLong()) {
                            if (primaryAdapter.hasNextMediaItem()) {
                                triggerCrossfade()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore — position queries can fail transiently
                }
                delay(200) // Check every 200ms, same as JVM implementation
            }
        }
    }

    private fun stopPositionMonitor() {
        positionMonitorJob?.cancel()
        positionMonitorJob = null
    }

    // ========== Crossfade Logic ==========

    private fun triggerCrossfade() {
        if (isCrossfading) return

        val nextIndex = getNextMediaItemIndex() ?: return
        val nextMediaItem = primaryAdapter.getMediaItemAt(nextIndex) ?: return

        // Don't crossfade for video content
        if (nextMediaItem.isVideo()) {
            Logger.d(TAG, "Skipping crossfade — next track is video")
            return
        }

        // Don't crossfade in repeat-one mode
        if (primaryAdapter.repeatMode == PlayerConstants.REPEAT_MODE_ONE) {
            Logger.d(TAG, "Skipping crossfade — repeat one mode")
            return
        }

        isCrossfading = true
        onCrossfadeStateChanged?.invoke(true)
        Logger.d(TAG, "Starting crossfade to index $nextIndex: ${nextMediaItem.metadata.title}")

        coroutineScope.launch {
            try {
                // Create secondary player and load the next track
                val secondary = exoPlayerFactory.createPlayer()
                secondaryExoPlayer = secondary

                // Build a MediaItem for the secondary player from the next track in the playlist.
                // We use the same mediaId/uri/cacheKey so the ResolvingDataSource cache is shared.
                val media3Item = nextMediaItem.toMedia3MediaItem()
                secondary.setMediaItem(media3Item)
                secondary.volume = 0f
                secondary.playWhenReady = true
                secondary.prepare()

                // Wait for secondary to be ready (with timeout)
                val ready = waitForPlayerReady(secondary, timeoutMs = 8000)
                if (!ready) {
                    Logger.w(TAG, "Secondary player failed to become ready, falling back to normal transition")
                    cleanupSecondaryPlayer()
                    isCrossfading = false
                    onCrossfadeStateChanged?.invoke(false)
                    return@launch
                }

                // Notify listeners immediately so UI updates to new track — matching JVM pattern.
                // This fires onMediaItemTransition on the handler, triggering all side effects
                // (format loading, lyrics, analytics, Discord RPC, volume normalization, etc.)
                suppressNextTransition = true
                listeners.forEach {
                    it.onMediaItemTransition(
                        nextMediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    )
                }

                Logger.d(TAG, "Secondary player ready, starting fade animation")

                // Perform the fade
                performCrossfade(secondary, nextIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Crossfade error: ${e.message}", e)
                cleanupSecondaryPlayer()
                isCrossfading = false
                onCrossfadeStateChanged?.invoke(false)
            }
        }
    }

    /**
     * Wait for a player to reach STATE_READY.
     */
    private suspend fun waitForPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (player.playbackState == Player.STATE_READY || player.isPlaying) {
                return true
            }
            if (player.playbackState == Player.STATE_IDLE) {
                // Error occurred
                return false
            }
            delay(50)
        }
        return false
    }

    private suspend fun performCrossfade(secondary: ExoPlayer, nextIndex: Int) {
        val steps = 50
        val delayPerStep = (crossfadeDurationMs / steps).toLong()
        val targetVolume = primaryExoPlayer.volume

        crossfadeJob?.cancel()
        crossfadeJob = coroutineScope.launch {
            try {
                for (step in 0..steps) {
                    if (!isActive) break

                    val progress = step.toFloat() / steps

                    // Fade out primary
                    primaryExoPlayer.volume = targetVolume * (1f - progress)

                    // Fade in secondary
                    secondary.volume = targetVolume * progress

                    delay(delayPerStep)
                }

                // Crossfade complete — finalize
                finalizeCrossfade(nextIndex, targetVolume)
            } catch (e: CancellationException) {
                Logger.d(TAG, "Crossfade cancelled during fade")
                cleanupSecondaryPlayer()
                // Restore primary volume
                primaryExoPlayer.volume = targetVolume
                isCrossfading = false
                onCrossfadeStateChanged?.invoke(false)
            }
        }
    }

    /**
     * Finalize: advance primary player to next track (cache-hit, near-instant),
     * restore its volume, and dispose the secondary player.
     */
    private fun finalizeCrossfade(nextIndex: Int, targetVolume: Float) {
        Logger.d(TAG, "Crossfade complete, advancing primary player to index $nextIndex")

        // The secondary player is now playing the next track at full volume.
        // We need to transition the primary ExoPlayer to the same track so MediaSession stays in sync.

        // Step 1: Record the secondary player's current position
        val secondaryPosition = secondaryExoPlayer?.currentPosition ?: 0L

        // Step 2: Advance the primary player to the next track at the secondary's position.
        // Since the data was already resolved/cached by the secondary player's ResolvingDataSource,
        // the primary player should load near-instantly from cache.
        primaryExoPlayer.volume = targetVolume
        primaryExoPlayer.seekTo(nextIndex, secondaryPosition)

        // Step 3: Dispose the secondary player
        cleanupSecondaryPlayer()

        isCrossfading = false
        onCrossfadeStateChanged?.invoke(false)

        Logger.d(TAG, "Crossfade finalized — primary player now on index $nextIndex")
    }

    private fun cleanupSecondaryPlayer() {
        secondaryExoPlayer?.let { player ->
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error cleaning up secondary player: ${e.message}")
            }
        }
        secondaryExoPlayer = null
    }

    fun cancelCrossfade() {
        if (!isCrossfading) return
        Logger.d(TAG, "Cancelling crossfade")
        crossfadeJob?.cancel()
        crossfadeJob = null
        cleanupSecondaryPlayer()
        suppressNextTransition = false
        // Restore primary volume to current volume property
        primaryExoPlayer.volume = primaryExoPlayer.volume.coerceAtLeast(0.01f).let {
            // If volume was faded to near-zero, restore to 1.0
            if (it < 0.1f) 1.0f else it
        }
        isCrossfading = false
        onCrossfadeStateChanged?.invoke(false)
    }

    /**
     * Get the next media item index, accounting for shuffle and repeat modes.
     */
    private fun getNextMediaItemIndex(): Int? {
        val currentIndex = primaryAdapter.currentMediaItemIndex
        val count = primaryAdapter.mediaItemCount
        if (count == 0) return null

        return when {
            primaryAdapter.repeatMode == PlayerConstants.REPEAT_MODE_ONE -> null // No crossfade for repeat one
            currentIndex < count - 1 -> currentIndex + 1
            primaryAdapter.repeatMode == PlayerConstants.REPEAT_MODE_ALL -> 0
            else -> null
        }
    }

    // ========== MediaPlayerInterface Delegation ==========
    // All normal operations delegate to primaryAdapter.
    // Crossfade-sensitive operations also cancel any in-progress crossfade.

    override fun play() {
        primaryAdapter.play()
        if (crossfadeEnabled && !isCrossfading) {
            startPositionMonitor()
        }
    }

    override fun pause() {
        cancelCrossfade()
        primaryAdapter.pause()
    }

    override fun stop() {
        cancelCrossfade()
        stopPositionMonitor()
        primaryAdapter.stop()
    }

    override fun seekTo(positionMs: Long) {
        cancelCrossfade()
        primaryAdapter.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        cancelCrossfade()
        primaryAdapter.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() {
        cancelCrossfade()
        primaryAdapter.seekBack()
    }

    override fun seekForward() {
        cancelCrossfade()
        primaryAdapter.seekForward()
    }

    override fun seekToNext() {
        cancelCrossfade()
        primaryAdapter.seekToNext()
    }

    override fun seekToPrevious() {
        cancelCrossfade()
        primaryAdapter.seekToPrevious()
    }

    override fun prepare() = primaryAdapter.prepare()

    // Media item management — delegate directly
    override fun setMediaItem(mediaItem: GenericMediaItem) {
        cancelCrossfade()
        primaryAdapter.setMediaItem(mediaItem)
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) = primaryAdapter.addMediaItem(mediaItem)

    override fun addMediaItem(index: Int, mediaItem: GenericMediaItem) = primaryAdapter.addMediaItem(index, mediaItem)

    override fun removeMediaItem(index: Int) = primaryAdapter.removeMediaItem(index)

    override fun moveMediaItem(fromIndex: Int, toIndex: Int) = primaryAdapter.moveMediaItem(fromIndex, toIndex)

    override fun clearMediaItems() {
        cancelCrossfade()
        primaryAdapter.clearMediaItems()
    }

    override fun replaceMediaItem(index: Int, mediaItem: GenericMediaItem) =
        primaryAdapter.replaceMediaItem(index, mediaItem)

    override fun getMediaItemAt(index: Int): GenericMediaItem? = primaryAdapter.getMediaItemAt(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = primaryAdapter.getCurrentMediaTimeLine()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int = primaryAdapter.getUnshuffledIndex(shuffledIndex)

    // Playback state — delegate to primary
    override val isPlaying: Boolean get() = primaryAdapter.isPlaying
    override val currentPosition: Long get() = primaryAdapter.currentPosition
    override val duration: Long get() = primaryAdapter.duration
    override val bufferedPosition: Long get() = primaryAdapter.bufferedPosition
    override val bufferedPercentage: Int get() = primaryAdapter.bufferedPercentage
    override val currentMediaItem: GenericMediaItem? get() = primaryAdapter.currentMediaItem
    override val currentMediaItemIndex: Int get() = primaryAdapter.currentMediaItemIndex
    override val mediaItemCount: Int get() = primaryAdapter.mediaItemCount
    override val contentPosition: Long get() = primaryAdapter.contentPosition
    override val playbackState: Int get() = primaryAdapter.playbackState

    // Navigation
    override fun hasNextMediaItem(): Boolean = primaryAdapter.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = primaryAdapter.hasPreviousMediaItem()

    // Playback modes
    override var shuffleModeEnabled: Boolean
        get() = primaryAdapter.shuffleModeEnabled
        set(value) { primaryAdapter.shuffleModeEnabled = value }

    override var repeatMode: Int
        get() = primaryAdapter.repeatMode
        set(value) { primaryAdapter.repeatMode = value }

    override var playWhenReady: Boolean
        get() = primaryAdapter.playWhenReady
        set(value) { primaryAdapter.playWhenReady = value }

    override var playbackParameters: GenericPlaybackParameters
        get() = primaryAdapter.playbackParameters
        set(value) { primaryAdapter.playbackParameters = value }

    // Audio settings — primary player's session ID (for LoudnessEnhancer and MediaSession)
    override val audioSessionId: Int get() = primaryAdapter.audioSessionId

    override var volume: Float
        get() = primaryAdapter.volume
        set(value) {
            primaryAdapter.volume = value
            // If crossfading, also update secondary player proportionally
            // (the crossfade animation handles its own volume, so we don't interfere)
        }

    override var skipSilenceEnabled: Boolean
        get() = primaryAdapter.skipSilenceEnabled
        set(value) { primaryAdapter.skipSilenceEnabled = value }

    // Listener management — own list + wrapper delegation to primaryAdapter
    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
        val wrapper = TransitionSuppressingListener(listener)
        listenerWrappers[listener] = wrapper
        primaryAdapter.addListener(wrapper)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
        listenerWrappers.remove(listener)?.let { wrapper ->
            primaryAdapter.removeListener(wrapper)
        }
    }

    // Release
    override fun release() {
        cancelCrossfade()
        stopPositionMonitor()
        listeners.clear()
        listenerWrappers.clear()
        primaryAdapter.release()
    }
}

/**
 * Extension to convert GenericMediaItem to Media3 MediaItem.
 * Duplicated here because the original is private in ExoPlayerAdapter.
 */
@UnstableApi
private fun GenericMediaItem.toMedia3MediaItem(): androidx.media3.common.MediaItem {
    val builder = androidx.media3.common.MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .apply {
                    metadata.title?.let { setTitle(it) }
                    metadata.artist?.let { setArtist(it) }
                    metadata.albumTitle?.let { setAlbumTitle(it) }
                    metadata.artworkUri?.let { setArtworkUri(it.toUri()) }
                    metadata.description?.let { setDescription(it) }
                }.build()
        )

    uri?.let { builder.setUri(it) }
    customCacheKey?.let { builder.setCustomCacheKey(it) }

    return builder.build()
}
