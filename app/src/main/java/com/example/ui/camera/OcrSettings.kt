package com.example.ui.camera

import android.content.Context
import kotlin.math.roundToLong

data class OcrSettings(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val fastModeFps: Int = DEFAULT_FAST_MODE_FPS,
    val slowModeFps: Float = DEFAULT_SLOW_MODE_FPS,
    val fuzzyMatchPercent: Int = DEFAULT_FUZZY_MATCH_PERCENT,
    val stationaryThreshold: Int = DEFAULT_STATIONARY_THRESHOLD,
    val maxVoteEnabled: Boolean = DEFAULT_MAX_VOTE_ENABLED
) {
    val normalIntervalMs: Long
        get() = (1000.0 / fastModeFps.coerceIn(MIN_FAST_FPS, MAX_FAST_FPS)).roundToLong().coerceAtLeast(50L)

    val slowIntervalMs: Long
        get() = (1000.0 / slowModeFps.coerceIn(MIN_SLOW_FPS, MAX_SLOW_FPS)).roundToLong().coerceAtLeast(200L)

    val similarityThreshold: Double
        get() = fuzzyMatchPercent.coerceIn(MIN_FUZZY_PERCENT, MAX_FUZZY_PERCENT) / 100.0

    companion object {
        const val DEFAULT_BATCH_SIZE = 1
        const val MIN_BATCH_SIZE = 1
        const val MAX_BATCH_SIZE = 5

        const val DEFAULT_FAST_MODE_FPS = 10
        const val MIN_FAST_FPS = 1
        const val MAX_FAST_FPS = 20

        const val DEFAULT_SLOW_MODE_FPS = 1.0f
        const val MIN_SLOW_FPS = 0.5f
        const val MAX_SLOW_FPS = 5.0f

        const val DEFAULT_FUZZY_MATCH_PERCENT = 80
        const val MIN_FUZZY_PERCENT = 50
        const val MAX_FUZZY_PERCENT = 100

        const val DEFAULT_STATIONARY_THRESHOLD = 10
        const val MIN_STATIONARY_THRESHOLD = 3
        const val MAX_STATIONARY_THRESHOLD = 30

        const val DEFAULT_MAX_VOTE_ENABLED = true
    }
}

class OcrSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): OcrSettings {
        return OcrSettings(
            batchSize = prefs.getInt(KEY_BATCH_SIZE, OcrSettings.DEFAULT_BATCH_SIZE)
                .coerceIn(OcrSettings.MIN_BATCH_SIZE, OcrSettings.MAX_BATCH_SIZE),
            fastModeFps = prefs.getInt(KEY_FAST_FPS, OcrSettings.DEFAULT_FAST_MODE_FPS)
                .coerceIn(OcrSettings.MIN_FAST_FPS, OcrSettings.MAX_FAST_FPS),
            slowModeFps = prefs.getFloat(KEY_SLOW_FPS, OcrSettings.DEFAULT_SLOW_MODE_FPS)
                .coerceIn(OcrSettings.MIN_SLOW_FPS, OcrSettings.MAX_SLOW_FPS),
            fuzzyMatchPercent = prefs.getInt(KEY_FUZZY_PERCENT, OcrSettings.DEFAULT_FUZZY_MATCH_PERCENT)
                .coerceIn(OcrSettings.MIN_FUZZY_PERCENT, OcrSettings.MAX_FUZZY_PERCENT),
            stationaryThreshold = prefs.getInt(KEY_STATIONARY_THRESHOLD, OcrSettings.DEFAULT_STATIONARY_THRESHOLD)
                .coerceIn(OcrSettings.MIN_STATIONARY_THRESHOLD, OcrSettings.MAX_STATIONARY_THRESHOLD),
            maxVoteEnabled = prefs.getBoolean(KEY_MAX_VOTE_ENABLED, OcrSettings.DEFAULT_MAX_VOTE_ENABLED)
        )
    }

    fun save(settings: OcrSettings) {
        prefs.edit()
            .putInt(KEY_BATCH_SIZE, settings.batchSize)
            .putInt(KEY_FAST_FPS, settings.fastModeFps)
            .putFloat(KEY_SLOW_FPS, settings.slowModeFps)
            .putInt(KEY_FUZZY_PERCENT, settings.fuzzyMatchPercent)
            .putInt(KEY_STATIONARY_THRESHOLD, settings.stationaryThreshold)
            .putBoolean(KEY_MAX_VOTE_ENABLED, settings.maxVoteEnabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "live_lens_ocr_settings"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_FAST_FPS = "fast_mode_fps"
        private const val KEY_SLOW_FPS = "slow_mode_fps"
        private const val KEY_FUZZY_PERCENT = "fuzzy_match_percent"
        private const val KEY_STATIONARY_THRESHOLD = "stationary_threshold"
        private const val KEY_MAX_VOTE_ENABLED = "max_vote_enabled"
    }
}
