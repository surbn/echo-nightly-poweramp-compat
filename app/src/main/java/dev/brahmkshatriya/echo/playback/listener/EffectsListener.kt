package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.copyTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.ln

@OptIn(UnstableApi::class)
class EffectsListener(
    private val exoPlayer: ExoPlayer,
    private val context: Context,
    private val audioSessionFlow: MutableStateFlow<Int>
) : Player.Listener {

    init {
        audioSessionFlow.value = exoPlayer.audioSessionId
        context.broadcastAudioSession(exoPlayer.audioSessionId)
    }

    private val settings: SharedPreferences = context.globalFx()
    private var oldSettings = settings
    private fun applyCustomEffects() {
        oldSettings.unregisterOnSharedPreferenceChangeListener(listener)
        val current = context.getFxPrefs(settings, exoPlayer.currentMediaItem?.mediaId?.hashCode())
            ?: settings
        oldSettings = current
        current.registerOnSharedPreferenceChangeListener(listener)
        applyPlayback(current)
        effects.applySettings(current)
    }

    private fun createEffects() = Effects(exoPlayer.audioSessionId)

    private fun applyPlayback(settings: SharedPreferences) {
        val index = settings.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f))
        val speed = speedRange.getOrNull(index) ?: 1f
        val pitch = if (settings.getBoolean(CHANGE_PITCH, true)) speed else 1f
        exoPlayer.playbackParameters =
            PlaybackParameters(speed, pitch)
    }

    private var effects: Effects = createEffects()
    private val listener = OnSharedPreferenceChangeListener { _, _ -> applyCustomEffects() }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyCustomEffects()
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        effects.release()
        context.closeBroadcastAudioSession()
        audioSessionFlow.value = audioSessionId
        effects = createEffects()
        effects.applySettings(oldSettings)
        context.broadcastAudioSession(audioSessionId)
    }

    class Effects(sessionId: Int) {
        private val equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        private val gain = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        private fun applyAudioSettings(settings: SharedPreferences) = runCatching {
            val bass = settings.getInt(BASS_BOOST, 0).coerceIn(0, 10)
            val eqEnabled = settings.getBoolean(EQ_ENABLED, false)
            val levels = IntArray(EQ_BAND_COUNT) { index ->
                settings.getInt("$EQ_BAND_PREFIX$index", 0).coerceIn(-12, 12)
            }

            equalizer?.apply {
                val range = bandLevelRange
                val bassValue = (bass * range.last().toDouble() / 10).roundToInt()
                val zero = numberOfBands.toDouble() * 2 / 3
                for (band in 0 until numberOfBands) {
                    val centerHz = getCenterFreq(band.toShort()).toDouble() / 1000.0
                    val eqMilliBel = if (eqEnabled) interpolateEq(centerHz, levels) * 100 else 0.0
                    val bassMilliBel = if (bass == 0) 0 else
                        (-(band - zero).pow(3) * bassValue / zero.pow(3)).roundToInt()
                    val combined = (eqMilliBel + bassMilliBel).roundToInt()
                        .coerceIn(range.first().toInt(), range.last().toInt())
                    setBandLevel(band.toShort(), combined.toShort())
                }
                setEnabled(eqEnabled || bass > 0)
            }

            gain?.apply {
                setEnabled(bass > 0)
                if (bass > 0) {
                    setTargetGain((bass.toDouble().pow(1.0 / 3.0) * 1600).roundToInt())
                }
            }
        }

        private fun interpolateEq(frequencyHz: Double, levels: IntArray): Double {
            val frequencies = EQ_CENTERS_HZ
            val x = ln(frequencyHz.coerceAtLeast(1.0))
            if (x <= ln(frequencies.first())) return levels.first().toDouble()
            if (x >= ln(frequencies.last())) return levels.last().toDouble()
            for (index in 0 until frequencies.lastIndex) {
                val left = ln(frequencies[index])
                val right = ln(frequencies[index + 1])
                if (x in left..right) {
                    val fraction = (x - left) / (right - left)
                    return levels[index] + (levels[index + 1] - levels[index]) * fraction
                }
            }
            return 0.0
        }

        fun release() {
            runCatching {
                equalizer?.release()
                gain?.release()
            }
        }

        fun applySettings(settings: SharedPreferences) {
            applyAudioSettings(settings)
        }
    }

    companion object {
        const val GLOBAL_FX = "global_fx"
        const val BASS_BOOST = "bass_boost"
        const val EQ_ENABLED = "eq_enabled"
        const val EQ_BAND_PREFIX = "eq_band_"
        const val EQ_BAND_COUNT = 5
        val EQ_CENTERS_HZ = doubleArrayOf(60.0, 250.0, 1000.0, 4000.0, 14000.0)
        const val PLAYBACK_SPEED = "playback_speed"
        val speedRange = listOf(
            0.1f, 0.175f, 0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 0.85f, 0.9f, 0.95f,
            1f, 1.05f, 1.1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 1.88f, 2f,
            2.33f, 2.5f, 3f, 4f, 8f, 16f, 32f, 64f
        )

        const val CHANGE_PITCH = "change_pitch"
        const val CUSTOM_EFFECTS = "custom_effects"

        fun Context.globalFx() = getSharedPreferences(GLOBAL_FX, Context.MODE_PRIVATE)!!
        fun Context.deleteGlobalFx() = deleteSharedPreferences(GLOBAL_FX)
        fun Context.getFxPrefs(settings: SharedPreferences, id: Int? = null): SharedPreferences? {
            if (id == null) return null
            val string = id.toString()
            val hasCustom = settings.getStringSet(CUSTOM_EFFECTS, emptySet())?.contains(string)
                ?: false
            return if (!hasCustom) null
            else getSharedPreferences("fx_$string", Context.MODE_PRIVATE)!!.apply {
                if (getBoolean("init", false)) return@apply
                settings.copyTo(this)
                edit { putBoolean("init", true) }
            }
        }

        fun Context.deleteFxPrefs(id: Int) =
            deleteSharedPreferences("fx_$id")
    }

    private fun Context.broadcastAudioSession(id: Int) {
        if (id <= 0) return
        sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        })
    }

    private fun Context.broadcastAudioSessionClose(id: Int) {
        if (id <= 0) return
        sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
        })
    }

    private fun Context.closeBroadcastAudioSession() {
        broadcastAudioSessionClose(audioSessionFlow.value)
    }

    fun release() {
        effects.release()
        context.closeBroadcastAudioSession()
    }
}
