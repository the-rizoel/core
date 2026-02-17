package com.maxrave.media3.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Biquad IIR filter supporting low-pass (LPF) and high-pass (HPF) filter types.
 * Based on Robert Bristow-Johnson's Audio EQ Cookbook.
 *
 * Designed for real-time DJ-style crossfade transitions:
 * - Outgoing track: LPF sweeps cutoff from 20 kHz -> ~300 Hz (treble fades, becomes muffled)
 * - Incoming track: HPF sweeps cutoff from ~300 Hz -> 20 Hz (bass fills in gradually)
 *
 * Supports stereo with independent per-channel state.
 * Thread-safe coefficient updates via [updateCoefficients].
 */
class BiquadFilter {

    enum class FilterType {
        LOW_PASS,
        HIGH_PASS,
    }

    // Filter coefficients (normalized: a0 = 1.0)
    @Volatile private var b0 = 1.0
    @Volatile private var b1 = 0.0
    @Volatile private var b2 = 0.0
    @Volatile private var a1 = 0.0
    @Volatile private var a2 = 0.0

    // Per-channel state (left)
    private var x1L = 0.0
    private var x2L = 0.0
    private var y1L = 0.0
    private var y2L = 0.0

    // Per-channel state (right)
    private var x1R = 0.0
    private var x2R = 0.0
    private var y1R = 0.0
    private var y2R = 0.0

    /**
     * Recalculate filter coefficients for the given parameters.
     *
     * @param cutoffHz Cutoff frequency in Hz.
     * @param sampleRate Sample rate in Hz (e.g. 44100, 48000).
     * @param q Quality factor. 0.707 (Butterworth) gives smooth, non-resonant rolloff.
     * @param type LOW_PASS or HIGH_PASS.
     */
    fun updateCoefficients(
        cutoffHz: Float,
        sampleRate: Int,
        q: Float = BUTTERWORTH_Q,
        type: FilterType,
    ) {
        val clampedCutoff = cutoffHz.coerceIn(20f, (sampleRate / 2f) - 1f)
        val omega = 2.0 * PI * clampedCutoff / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        val rawA0: Double
        when (type) {
            FilterType.LOW_PASS -> {
                b0 = (1.0 - cosOmega) / 2.0
                b1 = 1.0 - cosOmega
                b2 = (1.0 - cosOmega) / 2.0
                rawA0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }

            FilterType.HIGH_PASS -> {
                b0 = (1.0 + cosOmega) / 2.0
                b1 = -(1.0 + cosOmega)
                b2 = (1.0 + cosOmega) / 2.0
                rawA0 = 1.0 + alpha
                a1 = -2.0 * cosOmega
                a2 = 1.0 - alpha
            }
        }

        // Normalize so a0 = 1.0
        b0 /= rawA0
        b1 /= rawA0
        b2 /= rawA0
        a1 /= rawA0
        a2 /= rawA0
    }

    /**
     * Process a single mono sample through the filter (left channel state).
     */
    fun processSampleMono(input: Double): Double {
        val output = b0 * input + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L
        x1L = input
        y2L = y1L
        y1L = output
        return output
    }

    /**
     * Process a stereo sample pair through the filter.
     * Each channel maintains independent filter state.
     */
    fun processStereo(inputLeft: Double, inputRight: Double): Pair<Double, Double> {
        // Left channel
        val outL = b0 * inputLeft + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L
        x1L = inputLeft
        y2L = y1L
        y1L = outL

        // Right channel
        val outR = b0 * inputRight + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R
        x1R = inputRight
        y2R = y1R
        y1R = outR

        return outL to outR
    }

    /**
     * Reset all filter state (clears history).
     * Call when starting a new audio stream or disabling the filter.
     */
    fun reset() {
        x1L = 0.0; x2L = 0.0; y1L = 0.0; y2L = 0.0
        x1R = 0.0; x2R = 0.0; y1R = 0.0; y2R = 0.0
    }

    companion object {
        /** Butterworth Q factor: maximally flat passband, no resonance at cutoff. */
        const val BUTTERWORTH_Q = 0.707f
    }
}
