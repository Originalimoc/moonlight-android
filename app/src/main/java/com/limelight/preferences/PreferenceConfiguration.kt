package com.limelight.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.preference.PreferenceManager
import android.view.Display
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.nvstream.jni.MoonBridge.AudioConfiguration
import com.limelight.preferences.PreferenceConfiguration.AnalogStickForScrolling
import com.limelight.preferences.PreferenceConfiguration.FormatOption
import com.limelight.preferences.StreamSettings.Companion.displayCutoutP
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.text.toInt

class PreferenceConfiguration {
    enum class FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    }

    enum class AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    @JvmField
    var width: Int = 0
    @JvmField
    var height: Int = 0
    @JvmField
    var fps: Int = 0
    @JvmField
    var bitrate: Int = 0
    @JvmField
    var videoFormat: FormatOption? = null
    @JvmField
    var deadzonePercentage: Int = 0
    @JvmField
    var oscOpacity: Int = 0
    var stretchVideo: Boolean = false
    var enableSops: Boolean = false
    var playHostAudio: Boolean = false
    var disableWarnings: Boolean = false
    @JvmField
    var language: String? = null
    @JvmField
    var smallIconMode: Boolean = false
    @JvmField
    var multiController: Boolean = false
    @JvmField
    var usbDriver: Boolean = false
    @JvmField
    var flipFaceButtons: Boolean = false
    @JvmField
    var onscreenController: Boolean = false
    @JvmField
    var onlyL3R3: Boolean = false
    @JvmField
    var showGuideButton: Boolean = false
    var enableHdr: Boolean = false
    var enablePip: Boolean = false
    @JvmField
    var enablePerfOverlay: Boolean = false
    var enableLatencyToast: Boolean = false
    @JvmField
    var bindAllUsb: Boolean = false
    @JvmField
    var mouseEmulation: Boolean = false
    @JvmField
    var analogStickForScrolling: AnalogStickForScrolling? = null
    var mouseNavButtons: Boolean = false
    var unlockFps: Boolean = false
    @JvmField
    var vibrateOsc: Boolean = false
    @JvmField
    var vibrateFallbackToDevice: Boolean = false
    @JvmField
    var vibrateFallbackToDeviceStrength: Int = 0
    var touchscreenTrackpad: Boolean = false
    var audioConfiguration: AudioConfiguration? = null
    @JvmField
    var framePacing: Int = 0
    @JvmField
    var absoluteMouseMode: Boolean = false
    var enableAudioFx: Boolean = false
    var reduceRefreshRate: Boolean = false
    @JvmField
    var fullRange: Boolean = false
    @JvmField
    var gamepadMotionSensors: Boolean = false
    @JvmField
    var gamepadTouchpadAsMouse: Boolean = false
    @JvmField
    var gamepadMotionSensorsFallbackToDevice: Boolean = false

    companion object {
        private const val LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps"
        private const val LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround"

        const val RESOLUTION_PREF_STRING: String = "list_resolution"
        const val FPS_PREF_STRING: String = "list_fps"
        const val BITRATE_PREF_STRING: String = "seekbar_bitrate_kbps"
        private const val BITRATE_PREF_OLD_STRING = "seekbar_bitrate"
        private const val STRETCH_PREF_STRING = "checkbox_stretch_video"
        private const val SOPS_PREF_STRING = "checkbox_enable_sops"
        private const val DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings"
        private const val HOST_AUDIO_PREF_STRING = "checkbox_host_audio"
        private const val DEADZONE_PREF_STRING = "seekbar_deadzone"
        private const val OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity"
        private const val LANGUAGE_PREF_STRING = "list_languages"
        private const val SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode"
        private const val MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller"
        const val AUDIO_CONFIG_PREF_STRING: String = "list_audio_config"
        private const val USB_DRIVER_PREF_SRING = "checkbox_usb_driver"
        private const val VIDEO_FORMAT_PREF_STRING = "video_format"
        private const val ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls"
        private const val ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3"
        private const val SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button"
        private const val LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop"
        private const val ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr"
        private const val ENABLE_PIP_PREF_STRING = "checkbox_enable_pip"
        private const val ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay"
        private const val BIND_ALL_USB_STRING = "checkbox_usb_bind_all"
        private const val MOUSE_EMULATION_STRING = "checkbox_mouse_emulation"
        private const val ANALOG_SCROLLING_PREF_STRING = "analog_scrolling"
        private const val MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons"
        const val UNLOCK_FPS_STRING: String = "checkbox_unlock_fps"
        private const val VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc"
        private const val VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback"
        private const val VIBRATE_FALLBACK_STRENGTH_PREF_STRING =
            "seekbar_vibrate_fallback_strength"
        private const val FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons"
        private const val TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad"
        private const val LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast"
        private const val FRAME_PACING_PREF_STRING = "frame_pacing"
        private const val ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode"
        private const val ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx"
        private const val REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate"
        private const val FULL_RANGE_PREF_STRING = "checkbox_full_range"
        private const val GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING =
            "checkbox_gamepad_touchpad_as_mouse"
        private const val GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors"
        private const val GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback"

        const val DEFAULT_RESOLUTION: String = "1280x720"
        const val DEFAULT_FPS: String = "60"
        private const val DEFAULT_STRETCH = false
        private const val DEFAULT_SOPS = true
        private const val DEFAULT_DISABLE_TOASTS = false
        private const val DEFAULT_HOST_AUDIO = false
        private const val DEFAULT_DEADZONE = 7
        private const val DEFAULT_OPACITY = 90
        const val DEFAULT_LANGUAGE: String = "default"
        private const val DEFAULT_MULTI_CONTROLLER = true
        private const val DEFAULT_USB_DRIVER = true
        private const val DEFAULT_VIDEO_FORMAT = "auto"

        private const val ONSCREEN_CONTROLLER_DEFAULT = false
        private const val ONLY_L3_R3_DEFAULT = false
        private const val SHOW_GUIDE_BUTTON_DEFAULT = true
        private const val DEFAULT_ENABLE_HDR = false
        private const val DEFAULT_ENABLE_PIP = false
        private const val DEFAULT_ENABLE_PERF_OVERLAY = false
        private const val DEFAULT_BIND_ALL_USB = false
        private const val DEFAULT_MOUSE_EMULATION = true
        private const val DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right"
        private const val DEFAULT_MOUSE_NAV_BUTTONS = false
        private const val DEFAULT_UNLOCK_FPS = false
        private const val DEFAULT_VIBRATE_OSC = true
        private const val DEFAULT_VIBRATE_FALLBACK = false
        private const val DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100
        private const val DEFAULT_FLIP_FACE_BUTTONS = false
        private const val DEFAULT_TOUCHSCREEN_TRACKPAD = true
        private const val DEFAULT_AUDIO_CONFIG = "2" // Stereo
        private const val DEFAULT_LATENCY_TOAST = false
        private const val DEFAULT_FRAME_PACING = "latency"
        private const val DEFAULT_ABSOLUTE_MOUSE_MODE = false
        private const val DEFAULT_ENABLE_AUDIO_FX = false
        private const val DEFAULT_REDUCE_REFRESH_RATE = false
        private const val DEFAULT_FULL_RANGE = false
        private const val DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false
        private const val DEFAULT_GAMEPAD_MOTION_SENSORS = true
        private const val DEFAULT_GAMEPAD_MOTION_FALLBACK = false

        const val FRAME_PACING_MIN_LATENCY: Int = 0
        const val FRAME_PACING_BALANCED: Int = 1
        const val FRAME_PACING_CAP_FPS: Int = 2
        const val FRAME_PACING_MAX_SMOOTHNESS: Int = 3

        const val RES_360P: String = "640x360"
        const val RES_480P: String = "854x480"
        const val RES_720P: String = "1280x720"
        const val RES_1080P: String = "1920x1080"
        const val RES_1440P: String = "2560x1440"
        const val RES_4K: String = "3840x2160"
        const val RES_NATIVE = "native"
        const val RES_NATIVE_CUTOUT = "native cutout"
        const val RES_NATIVE_FULLSCREEN = "native fullscreen"

        fun isNativeResolution(width: Int, height: Int): Boolean {
            // It's not a native resolution if it matches an existing resolution option
            if (width == 640 && height == 360) {
                return false
            } else if (width == 854 && height == 480) {
                return false
            } else if (width == 1280 && height == 720) {
                return false
            } else if (width == 1920 && height == 1080) {
                return false
            } else if (width == 2560 && height == 1440) {
                return false
            } else if (width == 3840 && height == 2160) {
                return false
            }

            return true
        }

        // If we have a screen that has semi-square dimensions, we may want to change our behavior
        // to allow any orientation and vertical+horizontal resolutions.
        fun isSquarishScreen(width: Int, height: Int): Boolean {
            val longDim: Float = max(width, height).toFloat()
            val shortDim: Float = min(width, height).toFloat()

            // We just put the arbitrary cutoff for a square-ish screen at 1.3
            return longDim / shortDim < 1.3f
        }

        fun isSquarishScreen(display: Display): Boolean {
            var width: Int
            var height: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                width = display.mode.physicalWidth
                height = display.mode.physicalHeight
            } else {
                width = display.width
                height = display.height
            }

            return isSquarishScreen(width, height)
        }

        private fun convertFromLegacyResolutionString(resString: String): String {
            if (resString.equals("360p", ignoreCase = true)) {
                return RES_360P
            } else if (resString.equals("480p", ignoreCase = true)) {
                return RES_480P
            } else if (resString.equals("720p", ignoreCase = true)) {
                return RES_720P
            } else if (resString.equals("1080p", ignoreCase = true)) {
                return RES_1080P
            } else if (resString.equals("1440p", ignoreCase = true)) {
                return RES_1440P
            } else if (resString.equals("4K", ignoreCase = true)) {
                return RES_4K
            } else {
                // Should be unreachable
                return RES_720P
            }
        }

        private fun getResolutionString(width: Int, height: Int): String {
            return when (height) {
                360 -> RES_360P
                480 -> RES_480P
                720 -> RES_720P
                1080 -> RES_1080P
                1440 -> RES_1440P
                2160 -> RES_4K
                else -> RES_720P
            }
        }

        fun getDefaultBitrate(res: Pair<Int, Int>?, fpsString: String): Int {
            val width = res?.first ?: 1280
            val height = res?.second ?: 720
            val fps = fpsString.toInt()

            // This logic is shamelessly stolen from Moonlight Qt:
            // https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp

            // Don't scale bitrate linearly beyond 60 FPS. It's definitely not a linear
            // bitrate increase for frame rate once we get to values that high.
            val frameRateFactor = if (fps <= 60) {
                fps / 30f
            } else {
                (sqrt(fps.toFloat() / 60f) * 60f) / 30f
            }

            // TODO: Collect some empirical data to see if these defaults make sense.
            // We're just using the values that the Shield used, as we have for years.
            val pixelVals = intArrayOf(
                640 * 360,
                854 * 480,
                1280 * 720,
                1920 * 1080,
                2560 * 1440,
                3840 * 2160,
                -1,
            )
            val factorVals = intArrayOf(
                1,
                2,
                5,
                10,
                20,
                40,
                -1
            )

            // Calculate the resolution factor by linear interpolation of the resolution table
            var resolutionFactor: Float
            val pixels = width * height
            var i = 0
            while (true) {
                if (pixels == pixelVals[i]) {
                    // We can bail immediately for exact matches
                    resolutionFactor = factorVals[i].toFloat()
                    break
                } else if (pixels < pixelVals[i]) {
                    resolutionFactor = if (i == 0) {
                        // Never go below the lowest resolution entry
                        factorVals[i].toFloat()
                    } else {
                        // Interpolate between the entry greater than the chosen resolution (i) and the entry less than the chosen resolution (i-1)
                        ((pixels - pixelVals[i - 1]).toFloat() / (pixelVals[i] - pixelVals[i - 1])) * (factorVals[i] - factorVals[i - 1]) + factorVals[i - 1]
                    }
                    break
                } else if (pixelVals[i] == -1) {
                    // Never go above the highest resolution entry
                    resolutionFactor = factorVals[i - 1].toFloat()
                    break
                }
                i++
            }

            return (resolutionFactor * frameRateFactor).roundToInt() * 1000
        }

        @JvmStatic
        fun getDefaultSmallMode(context: Context): Boolean {
            val manager = context.packageManager
            if (manager != null) {
                // TVs shouldn't use small mode by default
                if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                    return false
                }

                // API 21 uses LEANBACK instead of TELEVISION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                        return false
                    }
                }
            }

            // Use small mode on anything smaller than a 7" tablet
            return context.resources.configuration.smallestScreenWidthDp < 500
        }

        @JvmStatic
        fun getDefaultBitrate(context: Context?): Int {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return getDefaultBitrate(
                parsePrefResolutionValue(prefs.getString(
                    RESOLUTION_PREF_STRING,
                    DEFAULT_RESOLUTION
                )!!).second,
                prefs.getString(
                    FPS_PREF_STRING,
                    DEFAULT_FPS
                )!!
            )
        }

        private fun getVideoFormatValue(context: Context?): FormatOption {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            val str: String = prefs.getString(
                VIDEO_FORMAT_PREF_STRING,
                DEFAULT_VIDEO_FORMAT
            )!!
            return if (str == "auto") {
                FormatOption.AUTO
            } else if (str == "forceav1") {
                FormatOption.FORCE_AV1
            } else if (str == "forceh265") {
                FormatOption.FORCE_HEVC
            } else if (str == "neverh265") {
                FormatOption.FORCE_H264
            } else {
                // Should never get here
                FormatOption.AUTO
            }
        }

        private fun getFramePacingValue(context: Context?): Int {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            // Migrate legacy never drop frames option to the new location
            if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
                val legacyNeverDropFrames =
                    prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false)
                prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(
                        FRAME_PACING_PREF_STRING,
                        if (legacyNeverDropFrames) "balanced" else "latency"
                    )
                    .apply()
            }

            val str: String = prefs.getString(
                FRAME_PACING_PREF_STRING,
                DEFAULT_FRAME_PACING
            )!!
            return if (str == "latency") {
                FRAME_PACING_MIN_LATENCY
            } else if (str == "balanced") {
                FRAME_PACING_BALANCED
            } else if (str == "cap-fps") {
                FRAME_PACING_CAP_FPS
            } else if (str == "smoothness") {
                FRAME_PACING_MAX_SMOOTHNESS
            } else {
                // Should never get here
                FRAME_PACING_MIN_LATENCY
            }
        }

        private fun getAnalogStickForScrollingValue(context: Context?): AnalogStickForScrolling {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            val str: String = prefs.getString(
                ANALOG_SCROLLING_PREF_STRING,
                DEFAULT_ANALOG_STICK_FOR_SCROLLING
            )!!
            return if (str == "right") {
                AnalogStickForScrolling.RIGHT
            } else if (str == "left") {
                AnalogStickForScrolling.LEFT
            } else {
                AnalogStickForScrolling.NONE
            }
        }

        @JvmStatic
        fun resetStreamingSettings(context: Context?) {
            // We consider resolution, FPS, bitrate, HDR, and video format as "streaming settings" here
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply()
        }

        @JvmStatic
        fun completeLanguagePreferenceMigration(context: Context?) {
            // Put our language option back to default which tells us that we've already migrated it
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply()
        }

        fun isShieldAtvFirmwareWithBrokenHdr(): Boolean {
            // This particular Shield TV firmware crashes when using HDR
            // https://www.nvidia.com/en-us/geforce/forums/notifications/comment/155192/
            return Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true) &&
                    Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395")
        }

        fun parsePrefResolutionValue(value: String): Pair<String?, Pair<Int, Int>?> {
            val parts = value.split("|")
            return when (parts.size) {
                1 -> {
                    // Only resolution part is present
                    val resolution = parseResolution(parts[0])
                    Pair("standard", resolution)
                }
                2 -> {
                    // Both mode and resolution parts are present
                    val resolution = parseResolution(parts[1])
                    Pair(parts[0], resolution)
                }
                else -> Pair(null, null) // Invalid format
            }
        }

        fun parseResolution(resolution: String): Pair<Int, Int>? {
            return try {
                val dimensions = resolution.split("x").map { it.toInt() }
                if (dimensions.size == 2) {
                    Pair(dimensions[0], dimensions[1])
                } else {
                    null
                }
            } catch (e: NumberFormatException) {
                null
            }
        }

        @JvmStatic
        fun readPreferences(context: Context): PreferenceConfiguration {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val config = PreferenceConfiguration()

            // Handle the new resolution format
            val resolutionValue = prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION)!!
            val (mode, resolution) = parsePrefResolutionValue(resolutionValue)

            config.width = resolution?.first ?: 1280
            config.height = resolution?.second ?: 720

            // Migrate legacy preferences to the new locations
            if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
                if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                    prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply()
                }
            }

            val str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null)
            if (str != null) {
                if (str == "360p30") {
                    config.width = 640
                    config.height = 360
                    config.fps = 30
                } else if (str == "360p60") {
                    config.width = 640
                    config.height = 360
                    config.fps = 60
                } else if (str == "720p30") {
                    config.width = 1280
                    config.height = 720
                    config.fps = 30
                } else if (str == "720p60") {
                    config.width = 1280
                    config.height = 720
                    config.fps = 60
                } else if (str == "1080p30") {
                    config.width = 1920
                    config.height = 1080
                    config.fps = 30
                } else if (str == "1080p60") {
                    config.width = 1920
                    config.height = 1080
                    config.fps = 60
                } else if (str == "4K30") {
                    config.width = 3840
                    config.height = 2160
                    config.fps = 30
                } else if (str == "4K60") {
                    config.width = 3840
                    config.height = 2160
                    config.fps = 60
                } else {
                    // Should never get here
                    config.width = 1280
                    config.height = 720
                    config.fps = 60
                }

                prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(
                        RESOLUTION_PREF_STRING,
                        getResolutionString(config.width, config.height)
                    )
                    .putString(FPS_PREF_STRING, "" + config.fps)
                    .apply()
            } else {
                // Use the new preference location
                val resStrOg = prefs.getString(
                    RESOLUTION_PREF_STRING,
                    DEFAULT_RESOLUTION
                )!!
                val res = parsePrefResolutionValue(resStrOg).second

                // Convert legacy resolution strings to the new style
                if (!resStrOg.contains("x")) {
                    val resStrNew = convertFromLegacyResolutionString(resStrOg)
                    prefs.edit().putString(RESOLUTION_PREF_STRING, resStrNew).apply()
                }

                config.width = res?.first ?: 1280
                config.height = res?.second?: 720

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Consider cutout when setting initial resolution
                    val dc = displayCutoutP
                    if (dc != null) {
                        val cutoutRect = dc.boundingRectTop
                        val cutoutWidth = cutoutRect.width()
                        val cutoutHeight = cutoutRect.height()
                        if (config.width > cutoutWidth && config.height > cutoutHeight) {
                            config.width -= cutoutWidth
                            config.height -= cutoutHeight
                        }
                    }
                }

                config.fps = prefs.getString(
                    FPS_PREF_STRING,
                    DEFAULT_FPS
                )!!.toInt()
            }

            if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
                // We need to write small icon mode's default to disk for the settings page to display
                // the current state of the option properly
                prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context))
                    .apply()
            }

            if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
                // Android 12 has a nasty bug that causes crashes when the app touches the InputDevice's
                // associated InputDeviceSensorManager (just calling getSensorManager() is enough).
                // As a workaround, we will override the default value for the gamepad motion sensor
                // option to disabled on Android 12 to reduce the impact of this bug.
                // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
                prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply()
            }

            // This must happen after the preferences migration to ensure the preferences are populated
            config.bitrate =
                prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000)
            if (config.bitrate == 0) {
                config.bitrate = getDefaultBitrate(context)
            }

            val audioConfig: String = prefs.getString(
                AUDIO_CONFIG_PREF_STRING,
                DEFAULT_AUDIO_CONFIG
            )!!
            if (audioConfig == "71") {
                config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
            } else if (audioConfig == "51") {
                config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
            } else  /* if (audioConfig.equals("2")) */ {
                config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO
            }

            config.videoFormat = getVideoFormatValue(context)
            config.framePacing = getFramePacingValue(context)

            config.analogStickForScrolling = getAnalogStickForScrollingValue(context)

            config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE)

            config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY)

            config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE)

            // Checkbox preferences
            config.disableWarnings =
                prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS)
            config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS)
            config.stretchVideo = prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH)
            config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO)
            config.smallIconMode =
                prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context))
            config.multiController =
                prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER)
            config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER)
            config.onscreenController =
                prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, ONSCREEN_CONTROLLER_DEFAULT)
            config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT)
            config.showGuideButton =
                prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT)
            config.enableHdr = prefs.getBoolean(
                ENABLE_HDR_PREF_STRING,
                DEFAULT_ENABLE_HDR
            ) && !isShieldAtvFirmwareWithBrokenHdr()
            config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP)
            config.enablePerfOverlay =
                prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY)
            config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB)
            config.mouseEmulation =
                prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION)
            config.mouseNavButtons =
                prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS)
            config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS)
            config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC)
            config.vibrateFallbackToDevice =
                prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK)
            config.vibrateFallbackToDeviceStrength = prefs.getInt(
                VIBRATE_FALLBACK_STRENGTH_PREF_STRING,
                DEFAULT_VIBRATE_FALLBACK_STRENGTH
            )
            config.flipFaceButtons =
                prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS)
            config.touchscreenTrackpad =
                prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD)
            config.enableLatencyToast =
                prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST)
            config.absoluteMouseMode =
                prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE)
            config.enableAudioFx =
                prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX)
            config.reduceRefreshRate =
                prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE)
            config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE)
            config.gamepadTouchpadAsMouse = prefs.getBoolean(
                GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING,
                DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE
            )
            config.gamepadMotionSensors =
                prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS)
            config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(
                GAMEPAD_MOTION_FALLBACK_PREF_STRING,
                DEFAULT_GAMEPAD_MOTION_FALLBACK
            )

            return config
        }
    }
}
