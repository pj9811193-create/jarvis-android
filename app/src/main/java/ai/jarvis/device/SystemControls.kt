package ai.jarvis.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings

/**
 * ============================  SYSTEM CONTROLS  ============================
 * Volume, flashlight, brightness, battery, and the settings panels Android
 * insists on for Wi-Fi/Bluetooth.
 *
 * Honest limits baked into the design:
 *   • Wi-Fi and Bluetooth cannot be toggled by a normal app on Android 10+.
 *     We open the settings panel instead — that is the supported behaviour.
 *   • Brightness writes need the special WRITE_SETTINGS grant; without it we
 *     send the user to the grant screen rather than silently failing.
 * =========================================================================
 */
class SystemControls(private val context: Context) {

    private val audio: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---------- volume ----------
    fun volumeUp(): String {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        return "Volume up."
    }

    fun volumeDown(): String {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return "Volume down."
    }

    fun volumeMax(): String {
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        return "Volume at maximum."
    }

    fun mute(): String {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Muted."
    }

    fun unmute(): String {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return "Unmuted."
    }

    // ---------- flashlight ----------
    private var torchOn = false

    fun flashlight(on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "There's no flashlight on this device, sir."

            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I couldn't control the flashlight, sir."
        }
    }

    fun isTorchOn(): Boolean = torchOn

    fun toggleFlashlight(): String = flashlight(!torchOn)

    // ---------- brightness ----------
    fun brightnessUp(): String = nudgeBrightness(+40)
    fun brightnessDown(): String = nudgeBrightness(-40)
    fun brightnessMax(): String = setBrightness(255)

    private fun nudgeBrightness(delta: Int): String {
        val current = currentBrightness() ?: return "I can't read the brightness, sir."
        return setBrightness((current + delta).coerceIn(10, 255))
    }

    private fun currentBrightness(): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) {
        null
    }

    private fun setBrightness(value: Int): String {
        if (!Settings.System.canWrite(context)) {
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "I need the \"modify system settings\" permission to change brightness — I've opened it for you, sir."
            } catch (e: Exception) {
                "I need system-settings permission to change brightness."
            }
        }
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            "Brightness set."
        } catch (e: Exception) {
            "I couldn't change the brightness, sir."
        }
    }

    // ---------- battery ----------
    fun battery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery is at $level percent."
    }

    // ---------- panels we are allowed to open ----------
    fun openWifi(): String {
        open(Settings.ACTION_WIFI_SETTINGS)
        return "Opening Wi-Fi settings — Android doesn't let apps toggle Wi-Fi directly, sir."
    }

    fun openBluetooth(): String {
        open(Settings.ACTION_BLUETOOTH_SETTINGS)
        return "Opening Bluetooth settings."
    }

    fun openBatterySaver(): String {
        open(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        return "Opening battery settings."
    }

    fun openSoundSettings(): String {
        open(Settings.ACTION_SOUND_SETTINGS)
        return "Opening sound settings."
    }

    private fun open(action: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // no such screen on this device
        }
    }
}
