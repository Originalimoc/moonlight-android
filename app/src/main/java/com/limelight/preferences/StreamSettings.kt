package com.limelight.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Vibrator
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.view.Display.HdrCapabilities
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.limelight.LimeLog
import com.limelight.PcView
import com.limelight.R
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.preferences.StreamSettings.SettingsFragment
import com.limelight.utils.Dialog
import com.limelight.utils.UiHelper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StreamSettings : Activity() {
    private var previousPrefs: PreferenceConfiguration? = null
    private var previousDisplayPixelCount = 0

    fun reloadSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode
            previousDisplayPixelCount = mode.physicalWidth * mode.physicalHeight
        }
        fragmentManager.beginTransaction().replace(
            R.id.stream_settings, SettingsFragment()
        ).commitAllowingStateLoss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previousPrefs = PreferenceConfiguration.Companion.readPreferences(this)

        UiHelper.setLocale(this)

        setContentView(R.layout.activity_stream_settings)

        UiHelper.notifyNewRootView(this)

        // Handle cutout for Android 9 (Pie) where necessary
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Handle cutout on Android 9 using the available insets
            val insets = window.decorView.rootWindowInsets
            if (insets != null) {
                displayCutoutP = insets.displayCutout
            }
        }

        reloadSettings()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // We have to use this hack on Android 9 because we don't have Display.getCutout()
        // which was added in Android 10.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Insets can be null when the activity is recreated on screen rotation
            // https://stackoverflow.com/questions/61241255/windowinsets-getdisplaycutout-is-null-everywhere-except-within-onattachedtowindo
            val insets = window.decorView.getRootWindowInsets()
            if (insets != null) {
                displayCutoutP = insets.displayCutout
            }
        }

        reloadSettings()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = windowManager.defaultDisplay.mode

            // If the display's physical pixel count has changed, we consider that it's a new display
            // and we should reload our settings (which include display-dependent values).
            //
            // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
            // switching between screens on a foldable device.
            if (mode.physicalWidth * mode.physicalHeight != previousDisplayPixelCount) {
                reloadSettings()
            }
        }
    }

    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    override fun onBackPressed() {
        finish()

        // Language changes are handled via configuration changes in Android 13+,
        // so manual activity relaunching is no longer required.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val newPrefs: PreferenceConfiguration =
                PreferenceConfiguration.Companion.readPreferences(this)
            if (newPrefs.language != previousPrefs!!.language) {
                // Restart the PC view to apply UI changes
                val intent = Intent(this, PcView::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent, null)
            }
        }
    }

    class SettingsFragment : PreferenceFragment() {
        private var nativeResolutionStartIndex = Int.Companion.MAX_VALUE
        private var nativeFramerateShown = false

        private fun getRecommendedCutoutArea(): Rect? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && activity != null) {
                val windowInsets = activity.window.decorView.rootWindowInsets
                if (windowInsets != null) {
                    val cutout = windowInsets.displayCutout
                    if (cutout != null) {
                        return cutout.boundingRectTop
                    }
                }
            }
            return null
        }

        private fun setValue(preferenceKey: String?, value: String?) {
            val pref = findPreference(preferenceKey) as ListPreference

            pref.setValue(value)
        }

        private fun appendPreferenceEntry(
            pref: ListPreference,
            newEntryName: String?,
            newEntryValue: String?
        ) {
            val newEntries = pref.entries.copyOf<CharSequence?>(pref.entries.size + 1)
            val newValues =
                pref.entryValues.copyOf<CharSequence?>(pref.entryValues.size + 1)

            // Add the new option
            newEntries[newEntries.size - 1] = newEntryName
            newValues[newValues.size - 1] = newEntryValue

            pref.entries = newEntries
            pref.entryValues = newValues
        }

        private fun addNativeResolutionEntry(
            nativeWidth: Int,
            nativeHeight: Int,
            insetsRemoved: Boolean,
            portrait: Boolean
        ) {
            val pref = findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING) as ListPreference

            val newName = buildString {
                append(if (insetsRemoved)
                    resources.getString(R.string.resolution_prefix_native)
                else
                    resources.getString(R.string.resolution_prefix_native_fullscreen))

                if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                    append(" ")
                    append(if (portrait)
                        resources.getString(R.string.resolution_prefix_native_portrait)
                    else
                        resources.getString(R.string.resolution_prefix_native_landscape))
                }
                append(" ($nativeWidth x $nativeHeight)")
            }

            // Incorporate mode into entryValue
            val mode = if (insetsRemoved) {
                "native_cutout"
            } else {
                "native"
            }
            val newValue = "$mode|$nativeWidth" + "x" + "$nativeHeight"

            if (pref.entryValues.any { it == newValue }) {
                return
            }

            if (pref.entryValues.size < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.entryValues.size
            }

            appendPreferenceEntry(pref, newName, newValue)
        }

        @SuppressLint("NewApi") // Hack for Android 9,
        private fun addNativeResolutionEntries(nativeWidth: Int, nativeHeight: Int, insetsRemoved: Boolean) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true)
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false)

            // Handle the cutout area if present
            val cutoutArea = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val insets = activity?.window?.decorView?.rootWindowInsets
                insets?.displayCutout
            } else {
                displayCutoutP
            }

            cutoutArea?.let {
                val widthInsets = it.safeInsetLeft + it.safeInsetRight
                val heightInsets = it.safeInsetTop + it.safeInsetBottom

                val adjustedWidth = nativeWidth - widthInsets
                val adjustedHeight = nativeHeight - heightInsets

                if (adjustedWidth > 0 && adjustedHeight > 0) {
                    // Add Native Cutout resolution
                    if (PreferenceConfiguration.isSquarishScreen(adjustedWidth, adjustedHeight)) {
                        addNativeResolutionEntry(adjustedHeight, adjustedWidth, true, true)
                    }
                    addNativeResolutionEntry(adjustedWidth, adjustedHeight, true, false)
                }
            }
        }

        private fun addNativeFrameRateEntry(framerate: Float) {
            val frameRateRounded = framerate.roundToInt()
            if (frameRateRounded == 0) {
                return
            }

            val pref =
                findPreference(PreferenceConfiguration.Companion.FPS_PREF_STRING) as ListPreference
            val fpsValue = frameRateRounded.toString()
            val fpsName = getResources().getString(R.string.resolution_prefix_native_fullscreen) +
                    " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")"

            // Check if the native frame rate is already present
            for (value in pref.entryValues) {
                if (fpsValue == value.toString()) {
                    // It is present in the default list, so don't add it again
                    nativeFramerateShown = false
                    return
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue)
            nativeFramerateShown = true
        }

        private fun removeValue(preferenceKey: String?, value: String?, onMatched: Runnable) {
            var matchingCount = 0

            val pref = findPreference(preferenceKey) as ListPreference

            // Count the number of matching entries we'll be removing
            for (seq in pref.entryValues) {
                if (seq.toString().equals(value, ignoreCase = true)) {
                    matchingCount++
                }
            }

            // Create the new arrays
            val entries = arrayOfNulls<CharSequence>(pref.entries.size - matchingCount)
            val entryValues = arrayOfNulls<CharSequence>(pref.entryValues.size - matchingCount)
            var outIndex = 0
            for (i in pref.entryValues.indices) {
                if (pref.entryValues[i].toString().equals(value, ignoreCase = true)) {
                    // Skip matching values
                    continue
                }

                entries[outIndex] = pref.entries[i]
                entryValues[outIndex] = pref.entryValues[i]
                outIndex++
            }

            if (pref.value.equals(value, ignoreCase = true)) {
                onMatched.run()
            }

            // Update the preference with the new list
            pref.entries = entries
            pref.entryValues = entryValues
        }

        private fun resetBitrateToDefault(prefs: SharedPreferences, res: Pair<Int, Int>?, fps: String?) {
            var resolution = res
            var framerate = fps
            if (resolution == null) {
                val storedRes = prefs.getString(
                    PreferenceConfiguration.RESOLUTION_PREF_STRING,
                    PreferenceConfiguration.DEFAULT_RESOLUTION
                )!!
                val (_, resolution) = PreferenceConfiguration.parsePrefResolutionValue(storedRes)
            }
            if (framerate == null) {
                framerate = prefs.getString(
                    PreferenceConfiguration.FPS_PREF_STRING,
                    PreferenceConfiguration.DEFAULT_FPS
                )
            }

            prefs.edit()
                .putInt(
                    PreferenceConfiguration.BITRATE_PREF_STRING,
                    PreferenceConfiguration.getDefaultBitrate(resolution, framerate.toString())
                )
                .apply()
        }

        override fun onCreateView(
            inflater: LayoutInflater?,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            UiHelper.applyStatusBarPadding(view)
            return view
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(R.xml.preferences)
            val screen = preferenceScreen

            // hide on-screen controls category on non touch screen devices
            if (!activity.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            ) {
                val category =
                    findPreference("category_onscreen_controls") as PreferenceCategory?
                screen.removePreference(category)
            }

            // Hide remote desktop mouse mode on pre-Oreo (which doesn't have pointer capture)
            // and NVIDIA SHIELD devices (which support raw mouse input in pointer capture mode)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                activity.packageManager.hasSystemFeature("com.nvidia.feature.shield")
            ) {
                val category =
                    findPreference("category_input_settings") as PreferenceCategory
                category.removePreference(findPreference("checkbox_absolute_mouse_mode"))
            }

            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val category =
                    findPreference("category_gamepad_settings") as PreferenceCategory
                category.removePreference(findPreference("checkbox_gamepad_motion_sensors"))
            }

            // Hide gamepad motion sensor fallback option if the device has no gyro or accelerometer
            if (!activity.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) &&
                !activity.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
            ) {
                val category =
                    findPreference("category_gamepad_settings") as PreferenceCategory
                category.removePreference(findPreference("checkbox_gamepad_motion_fallback"))
            }

            // Hide USB driver options on devices without USB host support
            if (!activity.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            ) {
                val category =
                    findPreference("category_gamepad_settings") as PreferenceCategory
                category.removePreference(findPreference("checkbox_usb_bind_all"))
                category.removePreference(findPreference("checkbox_usb_driver"))
            }

            // Remove PiP mode on devices pre-Oreo, where the feature is not available (some low RAM devices),
            // and on Fire OS where it violates the Amazon App Store guidelines for some reason.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !activity.packageManager
                    .hasSystemFeature("android.software.picture_in_picture") ||
                activity.packageManager.hasSystemFeature("com.amazon.software.fireos")
            ) {
                val category =
                    findPreference("category_ui_settings") as PreferenceCategory
                category.removePreference(findPreference("checkbox_enable_pip"))
            }

            // Fire TV apps are not allowed to use WebViews or browsers, so hide the Help category
            /*if (getActivity().getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
                PreferenceCategory category =
                        (PreferenceCategory) findPreference("category_help");
                screen.removePreference(category);
            }*/
            val category_gamepad_settings =
                findPreference("category_gamepad_settings") as PreferenceCategory
            // Remove the vibration options if the device can't vibrate
            if (!(activity.getSystemService(VIBRATOR_SERVICE) as Vibrator).hasVibrator()) {
                category_gamepad_settings.removePreference(findPreference("checkbox_vibrate_fallback"))
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"))
                // The entire OSC category may have already been removed by the touchscreen check above
                val category = findPreference("category_onscreen_controls") as PreferenceCategory?
                category?.removePreference(findPreference("checkbox_vibrate_osc"))
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !(activity.getSystemService(VIBRATOR_SERVICE) as Vibrator).hasAmplitudeControl()
            ) {
                // Remove the vibration strength selector of the device doesn't have amplitude control
                category_gamepad_settings.removePreference(findPreference("seekbar_vibrate_fallback_strength"))
            }

            val display = activity.windowManager.defaultDisplay
            var maxSupportedFps = display.refreshRate

            // Hide non-supported resolution/FPS combinations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var maxSupportedResW = 0

                var hasInsets = false
                // Always allow resolutions that are smaller or equal to the active
                // display resolution because decoders can report total non-sense to us.
                // For example, a p201 device reports:
                // AVC Decoder: OMX.amlogic.avc.decoder.awesome
                // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
                // AVC supported width range: 64 - 384
                // HEVC supported width range: 64 - 544
                for (candidate in display.supportedModes) {
                    // Some devices report their dimensions in the portrait orientation
                    // where height > width. Normalize these to the conventional width > height
                    // arrangement before we process them.

                    val width: Int = max(
                        candidate.physicalWidth,
                        candidate.physicalHeight
                    )
                    val height: Int = min(
                        candidate.physicalWidth,
                        candidate.physicalHeight
                    )

                    // Some TVs report strange values here, so let's avoid native resolutions on a TV
                    // unless they report greater than 4K resolutions.
                    if (!activity.packageManager
                            .hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                        (width > 3840 || height > 2160)
                    ) {
                        addNativeResolutionEntries(width, height, hasInsets)
                    }

                    if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840
                    } else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                        maxSupportedResW = 2560
                    } else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920
                    }

                    if (candidate.refreshRate > maxSupportedFps) {
                        maxSupportedFps = candidate.refreshRate
                    }
                }

                // This must be called to do runtime initialization before calling functions that evaluate
                // decoder lists.
                MediaCodecHelper.initialize(
                    context,
                    GlPreferences.readPreferences(context).glRenderer
                )

                val avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1)
                val hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)

                if (avcDecoder != null) {
                    val avcWidthRange =
                        avcDecoder.getCapabilitiesForType("video/avc").videoCapabilities
                            .supportedWidths

                    LimeLog.info("AVC supported width range: " + avcWidthRange.getLower() + " - " + avcWidthRange.getUpper())

                    // If 720p is not reported as supported, ignore all results from this API
                    if (avcWidthRange.contains(1280)) {
                        if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840
                        } else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920
                        } else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280
                        }
                    }
                }

                if (hevcDecoder != null) {
                    val hevcWidthRange =
                        hevcDecoder.getCapabilitiesForType("video/hevc").videoCapabilities
                            .supportedWidths

                    LimeLog.info("HEVC supported width range: " + hevcWidthRange.getLower() + " - " + hevcWidthRange.getUpper())

                    // If 720p is not reported as supported, ignore all results from this API
                    if (hevcWidthRange.contains(1280)) {
                        if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                            maxSupportedResW = 3840
                        } else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                            maxSupportedResW = 1920
                        } else if (maxSupportedResW < 1280) {
                            maxSupportedResW = 1280
                        }
                    }
                }

                LimeLog.info("Maximum resolution slot: $maxSupportedResW")

                if (maxSupportedResW != 0) {
                    if (maxSupportedResW < 3840) {
                        // 4K is unsupported
                        removeValue(
                            PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING,
                            PreferenceConfiguration.Companion.RES_4K,
                            object : Runnable {
                                override fun run() {
                                    val prefs =
                                        PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.activity)
                                    setValue(
                                        PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING,
                                        PreferenceConfiguration.Companion.RES_1440P
                                    )
                                    resetBitrateToDefault(prefs, null, null)
                                }
                            })
                    }
                    if (maxSupportedResW < 2560) {
                        // 1440p is unsupported
                        removeValue(
                            PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING,
                            PreferenceConfiguration.Companion.RES_1440P,
                            object : Runnable {
                                override fun run() {
                                    val prefs =
                                        PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.activity)
                                    setValue(
                                        PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING,
                                        PreferenceConfiguration.Companion.RES_1080P
                                    )
                                    resetBitrateToDefault(prefs, null, null)
                                }
                            })
                    }
                    if (maxSupportedResW < 1920) {
                        // 1080p is unsupported
                        removeValue(
                            PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING,
                            PreferenceConfiguration.Companion.RES_1080P,
                            object : Runnable {
                                override fun run() {
                                    val prefs =
                                        PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.activity)
                                    setValue(
                                        PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING,
                                        PreferenceConfiguration.Companion.RES_720P
                                    )
                                    resetBitrateToDefault(prefs, null, null)
                                }
                            })
                    }
                    // Never remove 720p
                }
            } else {
                // We can get the true metrics via the getRealMetrics() function (unlike the lies
                // that getWidth() and getHeight() tell to us).
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                val width: Int =
                    max(metrics.widthPixels, metrics.heightPixels)
                val height: Int =
                    min(metrics.widthPixels, metrics.heightPixels)
                addNativeResolutionEntries(width, height, false)
            }

            if (!PreferenceConfiguration.Companion.readPreferences(this.activity).unlockFps) {
                // We give some extra room in case the FPS is rounded down
                if (maxSupportedFps < 118) {
                    removeValue(
                        PreferenceConfiguration.Companion.FPS_PREF_STRING,
                        "120",
                        object : Runnable {
                            override fun run() {
                                val prefs =
                                    PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.activity)
                                setValue(PreferenceConfiguration.Companion.FPS_PREF_STRING, "90")
                                resetBitrateToDefault(prefs, null, null)
                            }
                        })
                }
                if (maxSupportedFps < 88) {
                    // 1080p is unsupported
                    removeValue(
                        PreferenceConfiguration.Companion.FPS_PREF_STRING,
                        "90",
                        object : Runnable {
                            override fun run() {
                                val prefs =
                                    PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.activity)
                                setValue(PreferenceConfiguration.Companion.FPS_PREF_STRING, "60")
                                resetBitrateToDefault(prefs, null, null)
                            }
                        })
                }
                // Never remove 30 FPS or 60 FPS
            }
            addNativeFrameRateEntry(maxSupportedFps)

            // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
            // that the unlock FPS option relies on to not massively increase latency.
            findPreference(PreferenceConfiguration.Companion.UNLOCK_FPS_STRING).onPreferenceChangeListener =
                object : OnPreferenceChangeListener {
                    override fun onPreferenceChange(
                        preference: Preference?,
                        newValue: Any?
                    ): Boolean {
                        // HACK: We need to let the preference change succeed before reinitializing to ensure
                        // it's reflected in the new layout.
                        val h = Handler()
                        h.postDelayed(object : Runnable {
                            override fun run() {
                                // Ensure the activity is still open when this timeout expires
                                val settingsActivity =
                                    this@SettingsFragment.activity as StreamSettings?
                                settingsActivity?.reloadSettings()
                            }
                        }, 500)

                        // Allow the original preference change to take place
                        return true
                    }
                }

            // Remove HDR preference for devices below Nougat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                LimeLog.info("Excluding HDR toggle based on OS")
                val category =
                    findPreference("category_advanced_settings") as PreferenceCategory
                category.removePreference(findPreference("checkbox_enable_hdr"))
            } else {
                val hdrCaps = display.hdrCapabilities

                // We must now ensure our display is compatible with HDR10
                var foundHdr10 = false
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == HdrCapabilities.HDR_TYPE_HDR10) {
                            foundHdr10 = true
                            break
                        }
                    }
                }

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities")
                    val category =
                        findPreference("category_advanced_settings") as PreferenceCategory
                    category.removePreference(findPreference("checkbox_enable_hdr"))
                } else if (PreferenceConfiguration.Companion.isShieldAtvFirmwareWithBrokenHdr()) {
                    LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware")
                    val category =
                        findPreference("category_advanced_settings") as PreferenceCategory
                    val hdrPref =
                        category.findPreference("checkbox_enable_hdr") as CheckBoxPreference
                    hdrPref.isEnabled = false
                    hdrPref.setChecked(false)
                    hdrPref.summary = "Update the firmware on your NVIDIA SHIELD Android TV to enable HDR"
                }
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference(PreferenceConfiguration.Companion.RESOLUTION_PREF_STRING).onPreferenceChangeListener =
                object : OnPreferenceChangeListener {
                    override fun onPreferenceChange(
                        preference: Preference,
                        newValue: Any?
                    ): Boolean {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

                        val valueStr = newValue as String

                        // Detect if this value is a native resolution option with mode
                        val (mode, resolution) = PreferenceConfiguration.parsePrefResolutionValue(valueStr)

                        // If mode is native, show the warning dialog
                        if (mode?.startsWith("native") == true) {
                            Dialog.displayDialog(
                                activity,
                                getString(R.string.title_native_res_dialog),
                                getString(R.string.text_native_res_dialog),
                                false
                            )
                        }

                        // Write the new bitrate value
                        resetBitrateToDefault(prefs, resolution, null)

                        // Allow the original preference change to take place
                        return true
                    }
                }
            findPreference(PreferenceConfiguration.Companion.FPS_PREF_STRING).onPreferenceChangeListener =
                object : OnPreferenceChangeListener {
                    override fun onPreferenceChange(
                        preference: Preference,
                        newValue: Any
                    ): Boolean {
                        val prefs =
                            PreferenceManager.getDefaultSharedPreferences(this@SettingsFragment.activity)
                        val valueStr = newValue as String?

                        // If this is native frame rate, show the warning dialog
                        val values = (preference as ListPreference).entryValues
                        if (nativeFramerateShown && values[values.size - 1].toString() == newValue.toString()) {
                            Dialog.displayDialog(
                                activity,
                                getResources().getString(R.string.title_native_fps_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false
                            )
                        }

                        // Write the new bitrate value
                        resetBitrateToDefault(prefs, null, valueStr)

                        // Allow the original preference change to take place
                        return true
                    }
                }
        }
    }

    companion object {
        // HACK for Android 9
        var displayCutoutP: DisplayCutout? = null
    }
}
