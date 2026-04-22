package com.aichatsdk.demo.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Manages speaker routing for TTS/voice playback.
 *
 * When no headset is connected, enables speakerphone so AudioTrack with
 * USAGE_VOICE_COMMUNICATION routes to the main speaker instead of the earpiece.
 * When a wired or BT headset is connected, disables speakerphone so the system
 * routes to the headset automatically.
 */
class AudioRouter(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var speakerEnabled = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isHeadset() }) {
                Log.d(TAG, "headset connected — disabling speakerphone")
                applySpeaker(false)
            }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isHeadset() }) {
                Log.d(TAG, "headset removed — re-enabling speakerphone if active")
                if (speakerEnabled) applySpeaker(true)
            }
        }
    }

    init {
        am.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun onPlaybackStart() {
        speakerEnabled = true
        if (!isHeadsetConnected()) applySpeaker(true)
    }

    fun onPlaybackStop() {
        speakerEnabled = false
        applySpeaker(false)
    }

    fun release() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        applySpeaker(false)
    }

    private fun applySpeaker(on: Boolean) {
        try {
            am.isSpeakerphoneOn = on
            Log.d(TAG, "setSpeakerphoneOn($on)")
        } catch (e: Throwable) {
            Log.w(TAG, "setSpeakerphoneOn failed", e)
        }
    }

    private fun isHeadsetConnected(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isHeadset() }

    private fun AudioDeviceInfo.isHeadset(): Boolean = type in HEADSET_TYPES

    companion object {
        private const val TAG = "AudioRouter"
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
