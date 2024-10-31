package com.limelight.preferences

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.preference.ListPreference
import android.util.AttributeSet
import android.view.WindowManager
import android.util.DisplayMetrics
import com.limelight.R
import kotlin.math.min
import kotlin.math.max

class ResolutionPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    private var nativeResolution: Point = Point()
    private var fullScreenResolution: Point = Point()

    init {
        detectResolutions(context)
        populateResolutionOptions(context)
    }

    private fun detectResolutions(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay

        // Get the resolution that accounts for the cutout (if any)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        nativeResolution.set(max(metrics.widthPixels, metrics.heightPixels),
            min(metrics.widthPixels, metrics.heightPixels))

        // Get the full screen resolution
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mode = display.mode
            fullScreenResolution.set(max(mode.physicalWidth, mode.physicalHeight),
                min(mode.physicalWidth, mode.physicalHeight))
        } else {
            // Fallback for older Android versions
            fullScreenResolution.set(nativeResolution.x, nativeResolution.y)
        }
    }

    private fun populateResolutionOptions(context: Context) {
        val entries = mutableListOf<CharSequence>()
        val entryValues = mutableListOf<CharSequence>()

        // Standard resolutions
        addResolutionOption(entries, entryValues, 1280, 720, "720p")
        addResolutionOption(entries, entryValues, 1920, 1080, "1080p")
        addResolutionOption(entries, entryValues, 2560, 1440, "1440p")
        addResolutionOption(entries, entryValues, 3840, 2160, "4K")

        // Native resolution (accounting for cutout)
        addNativeResolutionOption(entries, entryValues, context.getString(R.string.resolution_prefix_native) + " Pref", nativeResolution)

        // Full screen resolution (if different from native)
        if (fullScreenResolution.x != nativeResolution.x || fullScreenResolution.y != nativeResolution.y) {
            addNativeResolutionOption(entries, entryValues, context.getString(R.string.resolution_prefix_native_fullscreen) + " Pref", fullScreenResolution)
        }

        setEntries(entries.toTypedArray())
        setEntryValues(entryValues.toTypedArray())
    }

    private fun addResolutionOption(entries: MutableList<CharSequence>, entryValues: MutableList<CharSequence>, width: Int, height: Int, label: String) {
        entries.add("$label ($width x $height)")
        entryValues.add("${width}x${height}")
    }

    private fun addNativeResolutionOption(entries: MutableList<CharSequence>, entryValues: MutableList<CharSequence>, prefix: String, resolution: Point) {
        entries.add("$prefix (${resolution.x} x ${resolution.y})")
        entryValues.add("${prefix.lowercase()}|${resolution.x}x${resolution.y}")
    }
}
