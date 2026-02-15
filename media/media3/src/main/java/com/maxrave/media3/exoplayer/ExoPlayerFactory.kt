package com.maxrave.media3.exoplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory

/**
 * Factory for creating ExoPlayer instances with identical configuration.
 * Used by CrossfadeExoPlayerAdapter to create secondary players on demand.
 */
@UnstableApi
internal class ExoPlayerFactory(
    private val context: Context,
    private val audioAttributes: AudioAttributes,
    private val mergingMediaSourceFactory: MergingMediaSourceFactory,
    private val renderersFactory: DefaultRenderersFactory,
) {
    /**
     * Create a new ExoPlayer instance with the same configuration as the primary player.
     * The created player does NOT handle audio becoming noisy or manage audio focus,
     * since those are handled by the primary player.
     */
    fun createPlayer(): ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setAudioAttributes(audioAttributes, false) // false = don't manage audio focus (primary does)
            .setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * 4,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 4,
                        0,
                        0,
                    ).build(),
            ).setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(false) // Primary player handles this
            .setSeekForwardIncrementMs(5000)
            .setSeekBackIncrementMs(5000)
            .setMediaSourceFactory(mergingMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .build()
}
