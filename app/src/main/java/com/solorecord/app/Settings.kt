package com.solorecord.app

import android.content.Context

/** All settings stay on the device; no analytics, account or network. */
data class RecorderSettings(val longEdge: Int, val fps: Int, val microphone: Boolean, val prefix: String)

object Settings {
    private const val FILE = "local_options"
    private const val QUALITY = "quality"
    private const val FPS = "fps"
    private const val MIC = "microphone"
    private const val PREFIX = "prefix"
    const val LAST_VIDEO = "last_video_uri"

    fun read(context: Context): RecorderSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return RecorderSettings(
            longEdge = p.getInt(QUALITY, 1920).takeIf { it == 1280 || it == 1920 } ?: 1920,
            fps = p.getInt(FPS, 30).takeIf { it == 30 || it == 60 } ?: 30,
            microphone = p.getBoolean(MIC, false),
            prefix = RecordingPolicy.safePrefix(p.getString(PREFIX, "SoloRecord") ?: "SoloRecord")
        )
    }

    fun save(context: Context, settings: RecorderSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(QUALITY, settings.longEdge)
            .putInt(FPS, settings.fps)
            .putBoolean(MIC, settings.microphone)
            .putString(PREFIX, RecordingPolicy.safePrefix(settings.prefix))
            .apply()
    }

    fun lastVideo(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LAST_VIDEO, null)

    fun setLastVideo(context: Context, uri: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LAST_VIDEO, uri).apply()
    }
}
