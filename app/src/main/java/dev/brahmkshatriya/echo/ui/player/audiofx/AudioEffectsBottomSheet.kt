package dev.brahmkshatriya.echo.ui.player.audiofx

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.DialogPlayerAudioFxBinding
import dev.brahmkshatriya.echo.databinding.FragmentAudioFxBinding
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.BASS_BOOST
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.CHANGE_PITCH
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.CUSTOM_EFFECTS
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.EQ_BAND_COUNT
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.EQ_BAND_PREFIX
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.EQ_ENABLED
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.PLAYBACK_SPEED
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.deleteFxPrefs
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.getFxPrefs
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.globalFx
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.speedRange
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.RulerAdapter
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class AudioEffectsBottomSheet : BottomSheetDialogFragment() {

    var binding by autoCleared<DialogPlayerAudioFxBinding>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogPlayerAudioFxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewModel by activityViewModel<PlayerViewModel>()
        var mediaId: String? = null

        fun bind() {
            val settings = requireContext().globalFx()
            settings.edit {
                val customEffects = settings.getStringSet(CUSTOM_EFFECTS, null) ?: emptySet()
                putStringSet(CUSTOM_EFFECTS, customEffects + mediaId?.hashCode()?.toString())
            }
            binding.audioFxDescription.isVisible = mediaId != null
            val mediaSettings =
                requireContext().getFxPrefs(settings, mediaId?.hashCode()) ?: settings
            binding.audioFxFragment.bind(mediaSettings) { onEqualizerClicked() }
        }
        observe(viewModel.playerState.current) {
            mediaId = it?.mediaItem?.mediaId
            bind()
        }
        binding.topAppBar.setNavigationOnClickListener { dismiss() }
        binding.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_refresh -> {
                    val context = requireContext()
                    val id = mediaId ?: return@setOnMenuItemClickListener false
                    context.deleteFxPrefs(id.hashCode())
                    bind()
                    true
                }

                else -> false
            }
        }
    }

    companion object {
        @SuppressLint("SetTextI18n")
        fun FragmentAudioFxBinding.bind(
            settings: SharedPreferences, onEqualizerClicked: () -> Unit
        ) {
            val speed = settings.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f))
            val adapter = RulerAdapter(object : RulerAdapter.Listener<Int> {
                override fun intervalText(value: Int) = "${speedRange.getOrNull(value) ?: 1f}x"
                override fun onSelectItem(value: Int) {
                    speedValue.text = "${speedRange.getOrNull(value) ?: 1f}x"
                    settings.edit { putInt(PLAYBACK_SPEED, value) }
                }
            })

            speedRecycler.adapter = adapter
            adapter.submitList(List(speedRange.size) { index -> index to (index % 2 == 0) }, speed)

            pitchSwitch.isChecked = settings.getBoolean(CHANGE_PITCH, true)
            pitch.setOnClickListener {
                pitchSwitch.isChecked = !pitchSwitch.isChecked
            }
            pitchSwitch.setOnCheckedChangeListener { _, isChecked ->
                settings.edit { putBoolean(CHANGE_PITCH, isChecked) }
            }
            bassBoostSlider.value = settings.getInt(BASS_BOOST, 0).toFloat()
            bassBoostSlider.addOnChangeListener { _, value, _ ->
                settings.edit { putInt(BASS_BOOST, value.toInt()) }
            }
            bindEchoEqualizer(settings)
            equalizer.setOnClickListener { onEqualizerClicked() }
        }

        private data class EqPreset(val label: Int, val levels: IntArray)

        private fun FragmentAudioFxBinding.bindEchoEqualizer(settings: SharedPreferences) {
            val context = root.context
            val presets = listOf(
                EqPreset(R.string.eq_preset_flat, intArrayOf(0, 0, 0, 0, 0)),
                EqPreset(R.string.eq_preset_bass, intArrayOf(6, 5, 2, -1, -2)),
                EqPreset(R.string.eq_preset_pop, intArrayOf(-1, 3, 5, 2, -1)),
                EqPreset(R.string.eq_preset_rock, intArrayOf(4, 2, -1, 2, 4)),
                EqPreset(R.string.eq_preset_jazz, intArrayOf(3, 1, -2, 2, 4)),
                EqPreset(R.string.eq_preset_vocal, intArrayOf(-2, 2, 4, 3, 0)),
            )
            val bandNames = listOf("60 Hz", "250 Hz", "1 kHz", "4 kHz", "14 kHz")
            var updating = true

            eqSwitch.isChecked = settings.getBoolean(EQ_ENABLED, false)
            eqPresetGroup.removeAllViews()
            presets.forEach { preset ->
                val chip = Chip(context).apply {
                    text = context.getString(preset.label)
                    isCheckable = true
                    id = View.generateViewId()
                }
                eqPresetGroup.addView(chip)
                chip.setOnClickListener {
                    settings.edit {
                        putBoolean(EQ_ENABLED, true)
                        preset.levels.forEachIndexed { index, level ->
                            putInt("$EQ_BAND_PREFIX$index", level)
                        }
                    }
                    updating = true
                    eqSwitch.isChecked = true
                    eqBandsHost.childrenSliders().forEachIndexed { index, slider ->
                        slider.value = preset.levels[index].toFloat()
                    }
                    updating = false
                }
            }

            eqBandsHost.removeAllViews()
            repeat(EQ_BAND_COUNT) { index ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val bandLabel = TextView(context).apply {
                    text = bandNames[index]
                    textSize = 12f
                }
                row.addView(bandLabel, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))

                val slider = Slider(context).apply {
                    valueFrom = -12f
                    valueTo = 12f
                    stepSize = 1f
                    value = settings.getInt("$EQ_BAND_PREFIX$index", 0).coerceIn(-12, 12).toFloat()
                    contentDescription = bandNames[index]
                }
                row.addView(slider, LinearLayout.LayoutParams(0, dp(context, 48), 1f))

                val valueLabel = TextView(context).apply {
                    text = formatDb(slider.value.toInt())
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    textSize = 12f
                }
                row.addView(valueLabel, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
                eqBandsHost.addView(row)
                slider.addOnChangeListener { _, value, fromUser ->
                    valueLabel.text = formatDb(value.toInt())
                    if (fromUser && !updating) {
                        settings.edit { putInt("$EQ_BAND_PREFIX$index", value.toInt()) }
                        eqPresetGroup.clearCheck()
                    }
                }
            }

            eqSwitch.setOnCheckedChangeListener { _, enabled ->
                if (!updating) settings.edit { putBoolean(EQ_ENABLED, enabled) }
            }
            updating = false
        }

        private fun LinearLayout.childrenSliders(): List<Slider> = buildList {
            for (i in 0 until childCount) {
                val row = getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until row.childCount) {
                    (row.getChildAt(j) as? Slider)?.let(::add)
                }
            }
        }

        private fun formatDb(value: Int) = when {
            value > 0 -> "+$value dB"
            else -> "$value dB"
        }

        private fun dp(context: android.content.Context, value: Int) =
            (value * context.resources.displayMetrics.density).toInt()

        private fun openEqualizer(activity: ComponentActivity, sessionId: Int) {
            if (sessionId > 0) {
                activity.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, activity.packageName)
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                })
            }

            val equalizerPackage = "com.maxmpz.equalizer"
            val powerampIntent = activity.packageManager.getLaunchIntentForPackage(equalizerPackage)
            if (powerampIntent != null) {
                activity.startActivity(powerampIntent)
            } else {
                val storeUri = Uri.parse("market://details?id=$equalizerPackage")
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, storeUri))
                } catch (_: android.content.ActivityNotFoundException) {
                    activity.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$equalizerPackage")
                        )
                    )
                }
            }
        }

        fun Fragment.onEqualizerClicked() {
            val viewModel by activityViewModel<PlayerViewModel>()
            val sessionId = viewModel.playerState.session.value
            runCatching { openEqualizer(requireActivity(), sessionId) }.getOrElse {
                viewModel.run { viewModelScope.launch { app.throwFlow.emit(it) } }
            }
        }
    }
}
