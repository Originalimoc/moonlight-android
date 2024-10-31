package com.limelight

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Rational
import android.view.Display.HdrCapabilities
import android.view.InputDevice
import android.view.InputDevice.MotionRange
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnCapturedPointerListener
import android.view.View.OnGenericMotionListener
import android.view.View.OnSystemUiVisibilityChangeListener
import android.view.View.OnTouchListener
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.limelight.binding.PlatformBinding
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.binding.input.capture.InputCaptureManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.binding.input.driver.UsbDriverService.UsbDriverBinder
import com.limelight.binding.input.driver.UsbDriverService.UsbDriverStateListener
import com.limelight.binding.input.evdev.EvdevListener
import com.limelight.binding.input.touch.AbsoluteTouchContext
import com.limelight.binding.input.touch.RelativeTouchContext
import com.limelight.binding.input.touch.TouchContext
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.ComputerDetails.AddressTuple
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.GameGestures
import com.limelight.ui.StreamView
import com.limelight.ui.StreamView.InputCallbacks
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
import java.io.ByteArrayInputStream
import java.lang.Exception
import java.lang.reflect.InvocationTargetException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import kotlin.experimental.or
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt


class Game : Activity(), SurfaceHolder.Callback, OnGenericMotionListener, OnTouchListener,
    NvConnectionListener, EvdevListener, OnSystemUiVisibilityChangeListener, GameGestures,
    InputCallbacks, PerfOverlayListener, UsbDriverStateListener, View.OnKeyListener {
    private var lastButtonState = 0

    // Only 2 touches are supported
    private val touchContextMap = Array<TouchContext?>(2){ null }
    private var threeFingerDownTime: Long = 0

    private var controllerHandler: ControllerHandler? = null
    private var keyboardTranslator: KeyboardTranslator? = null
    private var virtualController: VirtualController? = null

    private var prefConfig: PreferenceConfiguration? = null
    private var tombstonePrefs: SharedPreferences? = null

    private var conn: NvConnection? = null
    private var spinner: SpinnerDialog? = null
    private var displayedFailureDialog = false
    private var connecting = false
    private var connected = false
    private var autoEnterPip = false
    private var surfaceCreated = false
    private var attemptedConnection = false
    private var suppressPipRefCount = 0
    private var pcName: String? = null
    private var appName: String? = null
    private var app: NvApp? = null
    private var desiredRefreshRate = 0f

    private var inputCaptureProvider: InputCaptureProvider? = null
    private var modifierFlags = 0
    private var grabbedInput = true
    private var cursorVisible = false
    private var waitingForAllModifiersUp = false
    private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var streamView: StreamView? = null
    private var lastAbsTouchUpTime: Long = 0
    private var lastAbsTouchDownTime: Long = 0
    private var lastAbsTouchUpX = 0f
    private var lastAbsTouchUpY = 0f
    private var lastAbsTouchDownX = 0f
    private var lastAbsTouchDownY = 0f

    private var isHidingOverlays = false
    private var notificationOverlayView: TextView? = null
    private var requestedNotificationOverlayVisibility = View.GONE
    private var performanceOverlayView: TextView? = null

    private var decoderRenderer: MediaCodecDecoderRenderer? = null
    private var reportedCrash = false

    private var highPerfWifiLock: WifiLock? = null
    private var lowLatencyWifiLock: WifiLock? = null

    private var connectedToUsbDriverService = false
    private val usbDriverServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
            val binder = iBinder as UsbDriverBinder
            binder.setListener(controllerHandler)
            binder.setStateListener(this@Game)
            binder.start()
            connectedToUsbDriverService = true
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            connectedToUsbDriverService = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Full-screen
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // If we're going to use immersive mode, we want to have
        // the entire screen
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        // Listen for UI visibility events
        window.decorView.setOnSystemUiVisibilityChangeListener(this)

        // Change volume button behavior
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Inflate the content
        setContentView(R.layout.activity_game)

        // Start the spinner
        spinner = SpinnerDialog.displayDialog(
            this, resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.conn_establishing_msg), true
        )

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this)
        tombstonePrefs = this@Game.getSharedPreferences("DecoderTombstone", 0)

        // Enter landscape unless we're on a square screen
        setPreferredOrientationForCurrentDisplay()

        if (prefConfig!!.stretchVideo || shouldIgnoreInsetsForResolution(
                prefConfig!!.width,
                prefConfig!!.height
            )
        ) {
            // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Listen for non-touch events on the game surface
        streamView = findViewById<StreamView>(R.id.surfaceView)
        streamView!!.setOnGenericMotionListener(this)
        streamView!!.setOnKeyListener(this)
        streamView!!.setInputCallbacks(this)

        // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        val backgroundTouchView = findViewById<View>(R.id.backgroundTouchView)
        backgroundTouchView.setOnTouchListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamView!!.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or  // Keyboards
                        InputDevice.SOURCE_CLASS_JOYSTICK or  // Gamepads
                        InputDevice.SOURCE_CLASS_POINTER or  // Touchscreens and mice (w/o pointer capture)
                        InputDevice.SOURCE_CLASS_POSITION or  // Touchpads
                        InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            )
            backgroundTouchView.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or  // Keyboards
                        InputDevice.SOURCE_CLASS_JOYSTICK or  // Gamepads
                        InputDevice.SOURCE_CLASS_POINTER or  // Touchscreens and mice (w/o pointer capture)
                        InputDevice.SOURCE_CLASS_POSITION or  // Touchpads
                        InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            )
        }

        notificationOverlayView = findViewById<TextView>(R.id.notificationOverlay)

        performanceOverlayView = findViewById<TextView>(R.id.performanceOverlay)

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView!!.setOnCapturedPointerListener(object : OnCapturedPointerListener {
                override fun onCapturedPointer(view: View?, motionEvent: MotionEvent): Boolean {
                    return handleMotionEvent(view, motionEvent)
                }
            })
        }

        // Warn the user if they're on a metered connection
        val connMgr = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connMgr.isActiveNetworkMetered) {
            displayTransientMessage(resources.getString(R.string.conn_metered))
        }

        // Make sure Wi-Fi is fully powered up
        val wifiMgr = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        try {
            highPerfWifiLock = wifiMgr.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Moonlight High Perf Lock"
            )
            highPerfWifiLock!!.setReferenceCounted(false)
            highPerfWifiLock!!.acquire()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "Moonlight Low Latency Lock"
                )
                lowLatencyWifiLock!!.setReferenceCounted(false)
                lowLatencyWifiLock!!.acquire()
            }
        } catch (e: SecurityException) {
            // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e.printStackTrace()
        }

        appName = this@Game.intent.getStringExtra(EXTRA_APP_NAME)
        pcName = this@Game.intent.getStringExtra(EXTRA_PC_NAME)

        val host = this@Game.intent.getStringExtra(EXTRA_HOST)
        val port = this@Game.intent.getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
        val httpsPort =
            this@Game.intent.getIntExtra(EXTRA_HTTPS_PORT, 0) // 0 is treated as unknown
        val appId =
            this@Game.intent.getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID)
        val uniqueId = this@Game.intent.getStringExtra(EXTRA_UNIQUEID)
        val appSupportsHdr = this@Game.intent.getBooleanExtra(EXTRA_APP_HDR, false)
        val derCertData = this@Game.intent.getByteArrayExtra(EXTRA_SERVER_CERT)

        app = NvApp(if (appName != null) appName else "app", appId, appSupportsHdr)

        var serverCert: X509Certificate? = null
        try {
            if (derCertData != null) {
                serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate?
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish()
            return
        }

        // Initialize the MediaCodec helper before creating the decoder
        val glPrefs = GlPreferences.readPreferences(this)
        MediaCodecHelper.initialize(this, glPrefs.glRenderer)

        // Check if the user has enabled HDR
        var willStreamHdr = false
        if (prefConfig!!.enableHdr) {
            // Start our HDR checklist
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val display = windowManager.defaultDisplay
                val hdrCaps = display.hdrCapabilities

                // We must now ensure our display is compatible with HDR10
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true
                            break
                        }
                    }
                }

                if (!willStreamHdr) {
                    // Nope, no HDR for us :(
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show()
            }
        }

        // Check if the user has enabled performance stats overlay
        if (prefConfig!!.enablePerfOverlay) {
            performanceOverlayView!!.visibility = View.VISIBLE
        }

        decoderRenderer = MediaCodecDecoderRenderer(
            this,
            prefConfig,
            object : CrashListener {
                @SuppressLint("ApplySharedPref")
                override fun notifyCrash(e: Exception?) {
                    // The MediaCodec instance is going down due to a crash
                    // let's tell the user something when they open the app again

                    // We must use commit because the app will crash when we return from this function

                    tombstonePrefs!!.edit()
                        .putInt("CrashCount", tombstonePrefs!!.getInt("CrashCount", 0) + 1).commit()
                    reportedCrash = true
                }
            },
            tombstonePrefs!!.getInt("CrashCount", 0),
            connMgr.isActiveNetworkMetered,
            willStreamHdr,
            glPrefs.glRenderer,
            this
        )

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer!!.isHevcMain10Hdr10Supported() && !decoderRenderer!!.isAv1Main10Supported()) {
            willStreamHdr = false
            Toast.makeText(this, "Decoder does not support HDR10 profile", Toast.LENGTH_LONG).show()
        }

        // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig!!.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer!!.isHevcSupported) {
            Toast.makeText(this, "No HEVC decoder found", Toast.LENGTH_LONG).show()
        }

        // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig!!.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer!!.isAv1Supported) {
            Toast.makeText(this, "No AV1 decoder found", Toast.LENGTH_LONG).show()
        }

        // H.264 is always supported
        var supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264
        if (decoderRenderer!!.isHevcSupported) {
            supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265
            if (willStreamHdr && decoderRenderer!!.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265_MAIN10
            }
        }
        if (decoderRenderer!!.isAv1Supported) {
            supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
            if (willStreamHdr && decoderRenderer!!.isAv1Main10Supported()) {
                supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN10
            }
        }

        var gamepadMask = ControllerHandler.getAttachedControllerMask(this).toInt()
        if (!prefConfig!!.multiController) {
            // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1
        }
        if (prefConfig!!.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask = gamepadMask or 1
        }

        // Set to the optimal mode for streaming
        val displayRefreshRate = prepareDisplayForRendering()
        LimeLog.info("Display refresh rate: $displayRefreshRate")

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        val roundedRefreshRate = displayRefreshRate.roundToInt()
        var chosenFrameRate = prefConfig!!.fps
        if (prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig!!.fps >= roundedRefreshRate) {
                if (prefConfig!!.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig!!.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    LimeLog.info("Using drop mode for FPS > Hz")
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig!!.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    LimeLog.info("Bogus refresh rate: $roundedRefreshRate")
                } else {
                    chosenFrameRate = roundedRefreshRate - 1
                    LimeLog.info("Adjusting FPS target for screen to $chosenFrameRate")
                }
            }
        }

        val config = StreamConfiguration.Builder()
            .setResolution(prefConfig!!.width, prefConfig!!.height)
            .setLaunchRefreshRate(prefConfig!!.fps)
            .setRefreshRate(chosenFrameRate)
            .setApp(app)
            .setBitrate(prefConfig!!.bitrate)
            .setEnableSops(prefConfig!!.enableSops)
            .enableLocalAudioPlayback(prefConfig!!.playHostAudio)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
            .setSupportedVideoFormats(supportedVideoFormats)
            .setAttachedGamepadMask(gamepadMask)
            .setClientRefreshRateX100((displayRefreshRate * 100).toInt())
            .setAudioConfiguration(prefConfig!!.audioConfiguration)
            .setColorSpace(decoderRenderer!!.preferredColorSpace)
            .setColorRange(decoderRenderer!!.preferredColorRange)
            .setPersistGamepadsAfterDisconnect(!prefConfig!!.multiController)
            .build()

        // Initialize the connection
        conn = NvConnection(
            applicationContext,
            AddressTuple(host, port),
            httpsPort, uniqueId, config,
            PlatformBinding.getCryptoProvider(this), serverCert
        )
        controllerHandler = ControllerHandler(this, conn, this, prefConfig)
        keyboardTranslator = KeyboardTranslator()

        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardTranslator, null)

        // Initialize touch contexts
        for (i in touchContextMap.indices) {
            if (!prefConfig!!.touchscreenTrackpad) {
                touchContextMap[i] = AbsoluteTouchContext(conn, i, streamView)
            } else {
                touchContextMap[i] = RelativeTouchContext(
                    conn, i,
                    REFERENCE_HORIZ_RES, REFERENCE_VERT_RES,
                    streamView, prefConfig
                )
            }
        }

        if (prefConfig!!.onscreenController) {
            // create virtual onscreen controller
            virtualController = VirtualController(
                controllerHandler,
                streamView!!.parent as FrameLayout?,
                this
            )
            virtualController!!.refreshLayout()
            virtualController!!.show()
        }

        if (prefConfig!!.usbDriver) {
            // Start the USB driver
            bindService(
                Intent(this, UsbDriverService::class.java),
                usbDriverServiceConnection, BIND_AUTO_CREATE
            )
        }

        if (!decoderRenderer!!.isAvcSupported) {
            if (spinner != null) {
                spinner!!.dismiss()
                spinner = null
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(
                this, resources.getString(R.string.conn_error_title),
                "This device or ROM doesn't support hardware accelerated H.264 playback.", true
            )
            return
        }

        // The connection will be started when the surface gets created
        streamView!!.holder.addCallback(this)
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun setPreferredOrientationForCurrentDisplay() {
        val display = windowManager.defaultDisplay

        // For semi-square displays, we use more complex logic to determine which orientation to use (if any)
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            var desiredOrientation = Configuration.ORIENTATION_UNDEFINED

            // OSC doesn't properly support portrait displays, so don't use it in portrait mode by default
            if (prefConfig!!.onscreenController) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE
            }

            // For native resolution, we will lock the orientation to the one that matches the specified resolution
            if (PreferenceConfiguration.isNativeResolution(
                    prefConfig!!.width,
                    prefConfig!!.height
                )
            ) {
                desiredOrientation = if (prefConfig!!.width > prefConfig!!.height) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            }

            if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
            } else if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
            } else {
                // If we don't have a reason to lock to portrait or landscape, allow any orientation
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
            }
        } else {
            // For regular displays, we always request landscape
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Set requested orientation for possible new screen size
        setPreferredOrientationForCurrentDisplay()

        if (virtualController != null) {
            // Refresh layout of OSC for possible new screen size
            virtualController!!.refreshLayout()
        }

        // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode) {
                isHidingOverlays = true

                if (virtualController != null) {
                    virtualController!!.hide()
                }

                performanceOverlayView!!.visibility = View.GONE
                notificationOverlayView!!.visibility = View.GONE

                // Disable sensors while in PiP mode
                controllerHandler!!.disableSensors()

                // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
                UiHelper.notifyStreamEnteringPiP(this)
            } else {
                isHidingOverlays = false

                // Restore overlays to previous state when leaving PiP
                if (virtualController != null) {
                    virtualController!!.show()
                }

                if (prefConfig!!.enablePerfOverlay) {
                    performanceOverlayView!!.visibility = View.VISIBLE
                }

                notificationOverlayView!!.visibility = requestedNotificationOverlayVisibility

                // Enable sensors again after exiting PiP
                controllerHandler!!.enableSensors()

                // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
                UiHelper.notifyStreamExitingPiP(this)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun getPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams? {
        val builder =
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(prefConfig!!.width, prefConfig!!.height))
                .setSourceRectHint(
                    Rect(
                        streamView!!.left, streamView!!.top,
                        streamView!!.right, streamView!!.bottom
                    )
                )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName)
                if (pcName != null) {
                    builder.setSubtitle(pcName)
                }
            } else if (pcName != null) {
                builder.setTitle(pcName)
            }
        }

        return builder.build()
    }

    private fun updatePipAutoEnter() {
        if (!prefConfig!!.enablePip) {
            return
        }

        val autoEnter = connected && suppressPipRefCount == 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter)!!)
        } else {
            autoEnterPip = autoEnter
        }
    }

    fun setMetaKeyCaptureState(enabled: Boolean) {
        // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try {
            val semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager")
            val getInstanceMethod = semWindowManager.getMethod("getInstance")
            val manager = getInstanceMethod.invoke(null)

            if (manager != null) {
                val parameterTypes = arrayOfNulls<Class<*>>(2)
                parameterTypes[0] = ComponentName::class.java
                parameterTypes[1] = Boolean::class.javaPrimitiveType
                val requestMetaKeyEventMethod =
                    semWindowManager.getDeclaredMethod("requestMetaKeyEvent", *parameterTypes)
                requestMetaKeyEventMethod.invoke(manager, this.componentName, enabled)
            } else {
                LimeLog.warning("SemWindowManager.getInstance() returned null")
            }
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
        } catch (e: NoSuchMethodException) {
            e.printStackTrace()
        } catch (e: InvocationTargetException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    public override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false)!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    override fun onPictureInPictureRequested(): Boolean {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false)!!)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider!!.onWindowFocusChanged(hasFocus)
    }

    private fun isRefreshRateEqualMatch(refreshRate: Float): Boolean {
        return refreshRate >= prefConfig!!.fps &&
                refreshRate <= prefConfig!!.fps + 3
    }

    private fun isRefreshRateGoodMatch(refreshRate: Float): Boolean {
        return refreshRate >= prefConfig!!.fps &&
                refreshRate.roundToInt() % prefConfig!!.fps <= 3
    }

    private fun shouldIgnoreInsetsForResolution(width: Int, height: Int): Boolean {
        // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = windowManager.defaultDisplay
            for (candidate in display.supportedModes) {
                // Ignore insets if this is an exact match for the display resolution
                if ((width == candidate.physicalWidth && height == candidate.physicalHeight) ||
                    (height == candidate.physicalWidth && width == candidate.physicalHeight)
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun mayReduceRefreshRate(): Boolean {
        return prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS || prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig!!.reduceRefreshRate)
    }

    private fun prepareDisplayForRendering(): Float {
        val display = windowManager.defaultDisplay
        val windowLayoutParams = window.attributes
        var displayRefreshRate: Float

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var bestMode = display.mode
            val isNativeResolutionStream =
                PreferenceConfiguration.isNativeResolution(prefConfig!!.width, prefConfig!!.height)
            var refreshRateIsGood = isRefreshRateGoodMatch(bestMode.refreshRate)
            var refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.refreshRate)

            LimeLog.info(
                "Current display mode: " + bestMode.physicalWidth + "x" +
                        bestMode.physicalHeight + "x" + bestMode.refreshRate
            )

            for (candidate in display.supportedModes) {
                val refreshRateReduced = candidate.refreshRate < bestMode.refreshRate
                val resolutionReduced =
                    candidate.physicalWidth < bestMode.physicalWidth ||
                            candidate.physicalHeight < bestMode.physicalHeight
                val resolutionFitsStream = candidate.physicalWidth >= prefConfig!!.width &&
                        candidate.physicalHeight >= prefConfig!!.height

                LimeLog.info(
                    "Examining display mode: " + candidate.physicalWidth + "x" +
                            candidate.physicalHeight + "x" + candidate.refreshRate
                )

                if (candidate.physicalWidth > 4096 && prefConfig!!.width <= 4096) {
                    // Avoid resolutions options above 4K to be safe
                    continue
                }

                // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig!!.width < 3840 && prefConfig!!.fps <= 60 && !isNativeResolutionStream) {
                    if (display.mode.physicalWidth != candidate.physicalWidth ||
                        display.mode.physicalHeight != candidate.physicalHeight
                    ) {
                        continue
                    }
                }

                // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig!!.fps > 60 && resolutionFitsStream)) {
                    continue
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(
                        candidate.refreshRate
                    )
                ) {
                    // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue
                } else if (refreshRateIsGood) {
                    // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!isRefreshRateGoodMatch(candidate.refreshRate)) {
                        continue
                    }

                    if (mayReduceRefreshRate()) {
                        // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate.refreshRate > bestMode.refreshRate) {
                            continue
                        }
                    } else {
                        // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced) {
                            continue
                        }
                    }
                } else if (!isRefreshRateGoodMatch(candidate.refreshRate)) {
                    // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced) {
                        continue
                    }
                } else {
                    // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

                bestMode = candidate
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.refreshRate)
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.refreshRate)
            }

            LimeLog.info(
                "Best display mode: " + bestMode.physicalWidth + "x" +
                        bestMode.physicalHeight + "x" + bestMode.refreshRate
            )

            // Only apply new window layout parameters if we've actually changed the display mode
            if (display.mode.modeId != bestMode.modeId) {
                // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || display.mode
                        .physicalWidth != bestMode.physicalWidth || display.mode
                        .physicalHeight != bestMode.physicalHeight
                ) {
                    // Apply the display mode change
                    windowLayoutParams.preferredDisplayModeId = bestMode.modeId
                    window.setAttributes(windowLayoutParams)
                } else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution")
                }
            } else {
                LimeLog.info("Current display mode is already the best display mode")
            }

            displayRefreshRate = bestMode.refreshRate
        } else {
            var bestRefreshRate = display.refreshRate
            for (candidate in display.supportedRefreshRates) {
                LimeLog.info("Examining refresh rate: $candidate")

                if (candidate > bestRefreshRate) {
                    // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig!!.fps <= 60) {
                        if (candidate >= 63) {
                            continue
                        }
                    }

                    bestRefreshRate = candidate
                }
            }

            LimeLog.info("Selected refresh rate: $bestRefreshRate")
            windowLayoutParams.preferredRefreshRate = bestRefreshRate
            displayRefreshRate = bestRefreshRate

            // Apply the refresh rate change
            window.setAttributes(windowLayoutParams)
        }

        // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        var aspectRatioMatch = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            val screenSize = Point(0, 0)
            display.getSize(screenSize)

            val screenAspectRatio = (screenSize.y.toDouble()) / screenSize.x
            val streamAspectRatio = (prefConfig!!.height.toDouble()) / prefConfig!!.width
            if (abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display")
                aspectRatioMatch = true
            }
        }

        if (prefConfig!!.stretchVideo || aspectRatioMatch) {
            // Set the surface to the size of the video
            streamView!!.holder.setFixedSize(prefConfig!!.width, prefConfig!!.height)
        } else {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView!!.setDesiredAspectRatio(prefConfig!!.width.toDouble() / prefConfig!!.height.toDouble())
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate

        return if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        ) {
            // TVs may take a few moments to switch refresh rates, and we can probably assume
            // it will be eventually activated.
            // TODO: Improve this
            displayRefreshRate
        } else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            min(
                windowManager.defaultDisplay.refreshRate,
                displayRefreshRate
            )
        }
    }

    @SuppressLint("InlinedApi")
    private val hideSystemUi: Runnable = object : Runnable {
        override fun run() {
            // TODO: Do we want to use WindowInsetsController here on R+ instead of
            // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

            // In multi-window mode on N+, we need to drop our layout flags or we'll
            // be drawing underneath the system UI.

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
                this@Game.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                // Use immersive mode
                this@Game.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    private fun hideSystemUi(delay: Int) {
        val h = window.decorView.getHandler()
        if (h != null) {
            h.removeCallbacks(hideSystemUi)
            h.postDelayed(hideSystemUi, delay.toLong())
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer!!.notifyVideoBackground()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer!!.notifyVideoForeground()
        }

        // Correct the system UI visibility flags
        hideSystemUi(50)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (controllerHandler != null) {
            controllerHandler!!.destroy()
        }
        if (keyboardTranslator != null) {
            val inputManager = getSystemService(INPUT_SERVICE) as InputManager
            inputManager.unregisterInputDeviceListener(keyboardTranslator)
        }

        if (lowLatencyWifiLock != null) {
            lowLatencyWifiLock!!.release()
        }
        if (highPerfWifiLock != null) {
            highPerfWifiLock!!.release()
        }

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            unbindService(usbDriverServiceConnection)
        }

        // Destroy the capture provider
        inputCaptureProvider!!.destroy()
    }

    override fun onPause() {
        if (isFinishing) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler!!.stop()
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false)
        }

        super.onPause()
    }

    override fun onStop() {
        super.onStop()

        SpinnerDialog.closeDialogs(this)
        Dialog.closeDialogs()

        if (virtualController != null) {
            virtualController!!.hide()
        }

        if (conn != null) {
            val videoFormat = decoderRenderer!!.activeVideoFormat

            displayedFailureDialog = true
            stopConnection()

            if (prefConfig!!.enableLatencyToast) {
                val averageEndToEndLat = decoderRenderer!!.getAverageEndToEndLatency()
                val averageDecoderLat = decoderRenderer!!.getAverageDecoderLatency()
                var message: String? = null
                if (averageEndToEndLat > 0) {
                    message =
                        resources.getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms"
                    if (averageDecoderLat > 0) {
                        message += " (" + resources.getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)"
                    }
                } else if (averageDecoderLat > 0) {
                    message =
                        resources.getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms"
                }

                // Add the video codec to the post-stream toast
                if (message != null) {
                    message += " ["

                    message += if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                        "H.264"
                    } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                        "HEVC"
                    } else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                        "AV1"
                    } else {
                        "UNKNOWN"
                    }

                    if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                        message += " HDR"
                    }

                    message += "]"
                }

                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs!!.getInt("CrashCount", 0) != 0) {
                tombstonePrefs!!.edit()
                    .putInt("CrashCount", 0)
                    .putInt("LastNotifiedCrashCount", 0)
                    .apply()
            }
        }

        finish()
    }

    private fun setInputGrabState(grab: Boolean) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider!!.enableCapture()

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider!!.showCursor()
            }
        } else {
            inputCaptureProvider!!.disableCapture()
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab)

        grabbedInput = grab
    }

    private val toggleGrab: Runnable = object : Runnable {
        override fun run() {
            setInputGrabState(!grabbedInput)
        }
    }

    // Returns true if the key stroke was consumed
    private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        var modifierMask = 0
        var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
        } else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
        } else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
        } else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_META_RIGHT
        ) {
            modifierMask = KeyboardPacket.MODIFIER_META.toInt()
        } else {
            nonModifierKeyCode = androidKeyCode
        }

        if (down) {
            this.modifierFlags = this.modifierFlags or modifierMask
        } else {
            this.modifierFlags = this.modifierFlags and modifierMask.inv()
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true
            } else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down
            } else {
                // When all modifiers are up, perform the special action
                when (specialKeyCode) {
                    KeyEvent.KEYCODE_Z -> {
                        val h = window.decorView.getHandler()
                        h?.postDelayed(toggleGrab, 250)
                    }

                    KeyEvent.KEYCODE_Q -> finish()
                    KeyEvent.KEYCODE_C -> {
                        if (!grabbedInput) {
                            inputCaptureProvider!!.enableCapture()
                            grabbedInput = true
                        }
                        cursorVisible = !cursorVisible
                        if (cursorVisible) {
                            inputCaptureProvider!!.showCursor()
                        } else {
                            inputCaptureProvider!!.hideCursor()
                        }
                    }

                    else -> {}
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
            }
        } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
            (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
            (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
        ) {
            when (androidKeyCode) {
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode
                    waitingForAllModifiersUp = true
                    return true
                }

                else ->                     // This isn't a special combo that we consume on the client side
                    return false
            }
        }

        // Not a special combo
        return false
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private fun getModifierState(event: KeyEvent): Byte {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        var modifier = getModifierState()
        if (event.isShiftPressed) {
            modifier = modifier or KeyboardPacket.MODIFIER_SHIFT
        }
        if (event.isCtrlPressed) {
            modifier = modifier or KeyboardPacket.MODIFIER_CTRL
        }
        if (event.isAltPressed) {
            modifier = modifier or KeyboardPacket.MODIFIER_ALT
        }
        if (event.isMetaPressed) {
            modifier = modifier or KeyboardPacket.MODIFIER_META
        }
        return modifier
    }

    private fun getModifierState(): Byte {
        return modifierFlags.toByte()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyDown(event) || super.onKeyDown(keyCode, event)
    }

    override fun handleKeyDown(event: KeyEvent): Boolean {
        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.

            if (!prefConfig!!.mouseNavButtons) {
                conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true
        }

        var handled = false

        if (ControllerHandler.isGameControllerDevice(event.device)) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler!!.handleButtonDown(event)
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.keyCode, true)) {
                return true
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            val translated = keyboardTranslator!!.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                // Make sure it has a valid Unicode representation and it's not a dead character
                // (which we don't support). If those are true, we can send it as UTF-8 text.
                //
                // NB: We need to be sure this happens before the getRepeatCount() check because
                // UTF-8 events don't auto-repeat on the host side.
                val unicodeChar = event.unicodeChar
                if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                    conn!!.sendUtf8Text("" + unicodeChar.toChar())
                    return true
                }

                return false
            }

            // Eat repeat down events
            if (event.repeatCount > 0) {
                return true
            }

            conn!!.sendKeyboardInput(
                translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                if (keyboardTranslator!!.hasNormalizedMapping(
                        event.keyCode,
                        event.deviceId
                    )
                ) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyUp(event) || super.onKeyUp(keyCode, event)
    }

    override fun handleKeyUp(event: KeyEvent): Boolean {
        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.

            if (!prefConfig!!.mouseNavButtons) {
                conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler!!.handleButtonUp(event)
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false)) {
                return true
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false
            }

            val translated = keyboardTranslator!!.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                // If we sent this event as UTF-8 on key down, also report that it was handled
                // when we get the key up event for it.
                val unicodeChar = event.unicodeChar
                return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
            }

            conn!!.sendKeyboardInput(
                translated, KeyboardPacket.KEY_UP, getModifierState(event),
                if (keyboardTranslator!!.hasNormalizedMapping(
                        event.keyCode,
                        event.deviceId
                    )
                ) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun handleKeyMultiple(event: KeyEvent): Boolean {
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || event.characters == null) {
            return false
        }

        conn!!.sendUtf8Text(event.characters)
        return true
    }

    private fun getTouchContext(actionIndex: Int): TouchContext? {
        return if (actionIndex < touchContextMap.size) {
            touchContextMap[actionIndex]
        } else {
            null
        }
    }

    override fun toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay")
        val inputManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.toggleSoftInput(0, 0)
    }

    private fun getLiTouchTypeFromEvent(event: MotionEvent): Byte {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> return MoonBridge.LI_TOUCH_EVENT_DOWN

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> return if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                MoonBridge.LI_TOUCH_EVENT_CANCEL
            } else {
                MoonBridge.LI_TOUCH_EVENT_UP
            }

            MotionEvent.ACTION_MOVE -> return MoonBridge.LI_TOUCH_EVENT_MOVE

            MotionEvent.ACTION_CANCEL ->                 // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL

            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> return MoonBridge.LI_TOUCH_EVENT_HOVER

            MotionEvent.ACTION_HOVER_EXIT -> return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY

            else -> return -1
        }
    }

    private fun getStreamViewRelativeNormalizedXY(
        view: View?,
        event: MotionEvent,
        pointerIndex: Int
    ): FloatArray {
        var normalizedX = event.getX(pointerIndex)
        var normalizedY = event.getY(pointerIndex)

        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view !== streamView) {
            normalizedX -= streamView!!.x
            normalizedY -= streamView!!.y
        }

        normalizedX = max(normalizedX, 0.0f)
        normalizedY = max(normalizedY, 0.0f)

        normalizedX = min(normalizedX, streamView!!.width.toFloat().toFloat())
        normalizedY = min(normalizedY, streamView!!.height.toFloat().toFloat())

        normalizedX /= streamView!!.width.toFloat().toFloat()
        normalizedY /= streamView!!.height.toFloat().toFloat()

        return floatArrayOf(normalizedX, normalizedY)
    }

    private fun getStreamViewNormalizedContactArea(
        event: MotionEvent,
        pointerIndex: Int
    ): FloatArray {
        var orientation: Float

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        orientation = if (event.device == null || event.device
                .getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) == null
        ) {
            (Math.PI / 4).toFloat()
        } else {
            event.getOrientation(pointerIndex)
        }

        var contactAreaMajor: Float
        var contactAreaMinor: Float
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
                contactAreaMajor = event.getToolMajor(pointerIndex)
                contactAreaMinor = event.getToolMinor(pointerIndex)
            }

            else -> {
                contactAreaMajor = event.getTouchMajor(pointerIndex)
                contactAreaMinor = event.getTouchMinor(pointerIndex)
            }
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        val contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation)

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        val contactAreaMinorCartesian =
            polarToCartesian(contactAreaMinor, (orientation + (Math.PI / 2)).toFloat())

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = min(
            abs(contactAreaMajorCartesian[0]),
            streamView!!.width.toFloat()
        ) / streamView!!.width.toFloat()
        contactAreaMinorCartesian[0] = min(
            abs(contactAreaMinorCartesian[0]),
            streamView!!.width.toFloat()
        ) / streamView!!.width.toFloat()
        contactAreaMajorCartesian[1] = min(
            abs(contactAreaMajorCartesian[1]),
            streamView!!.height.toFloat()
        ) / streamView!!.height.toFloat()
        contactAreaMinorCartesian[1] = min(
            abs(contactAreaMinorCartesian[1]),
            streamView!!.height.toFloat()
        ) / streamView!!.height.toFloat()

        // Convert the normalized values back into polar coordinates
        return floatArrayOf(
            cartesianToR(contactAreaMajorCartesian),
            cartesianToR(contactAreaMinorCartesian)
        )
    }

    private fun sendPenEventForPointer(
        view: View?,
        event: MotionEvent,
        eventType: Byte,
        toolType: Byte,
        pointerIndex: Int
    ): Boolean {
        var penButtons: Byte = 0
        if ((event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons = penButtons or MoonBridge.LI_PEN_BUTTON_PRIMARY
        }
        if ((event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons = penButtons or MoonBridge.LI_PEN_BUTTON_SECONDARY
        }

        var tiltDegrees = MoonBridge.LI_TILT_UNKNOWN
        val dev = event.device
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null) {
                tiltDegrees = Math.toDegrees(
                    event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()
                ).toInt().toByte()
            }
        }

        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex)
        return conn!!.sendPenEvent(
            eventType, toolType, penButtons,
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex), tiltDegrees
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    private fun trySendPenEvent(view: View?, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) {
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            var handledStylusEvent = false
            for (i in 0 until event.pointerCount) {
                val toolType = convertToolTypeToStylusToolType(event, i)
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue
                } else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false
                }
            }
            return handledStylusEvent
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn!!.sendPenEvent(
                MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, 0.toByte(),
                0f, 0f, 0f, 0f, 0f,
                MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN
            ) != MoonBridge.LI_ERR_UNSUPPORTED
        } else {
            // Up, Down, and Hover events are specific to the action index
            val toolType = convertToolTypeToStylusToolType(event, event.actionIndex)
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.actionIndex)
        }
    }

    private fun sendTouchEventForPointer(
        view: View?,
        event: MotionEvent,
        eventType: Byte,
        pointerIndex: Int
    ): Boolean {
        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex)
        return conn!!.sendTouchEvent(
            eventType, event.getPointerId(pointerIndex),
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex)
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    private fun trySendTouchEvent(view: View?, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) {
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (i in 0 until event.pointerCount) {
                if (!sendTouchEventForPointer(view, event, eventType, i)) {
                    return false
                }
            }
            return true
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn!!.sendTouchEvent(
                MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                0f, 0f, 0f, 0f, 0f,
                MoonBridge.LI_ROT_UNKNOWN
            ) != MoonBridge.LI_ERR_UNSUPPORTED
        } else {
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.actionIndex)
        }
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    private fun handleMotionEvent(view: View?, event: MotionEvent): Boolean {
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false
        }

        val eventSource = event.source
        val deviceSources = if (event.device != null) event.device.sources else 0
        if ((eventSource and InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler!!.handleMotionEvent(event)) {
                return true
            }
        } else if ((deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler!!.tryHandleTouchpadEvent(
                event
            )
        ) {
            return true
        } else if ((eventSource and InputDevice.SOURCE_CLASS_POINTER) != 0 || (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) {
            // This case is for mice and non-finger touch devices
            if (eventSource == InputDevice.SOURCE_MOUSE || (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 ||  // SOURCE_TOUCHPAD
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                (event.pointerCount >= 1 &&
                        (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(
                            0
                        ) == MotionEvent.TOOL_TYPE_ERASER)) || eventSource == 12290
            )  // 12290 = Samsung DeX mode desktop mouse
            {
                var buttonState = event.buttonState
                var changedButtons = buttonState xor lastButtonState

                // The DeX touchpad on the Fold 4 sends proper right click events using BUTTON_SECONDARY,
                // but doesn't send BUTTON_PRIMARY for a regular click. Instead it sends ACTION_DOWN/UP,
                // so we need to fix that up to look like a sane input event to process it correctly.
                if (eventSource == 12290) {
                    buttonState = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        buttonState or MotionEvent.BUTTON_PRIMARY
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        buttonState and MotionEvent.BUTTON_PRIMARY.inv()
                    } else {
                        // We may be faking the primary button down from a previous event,
                        // so be sure to add that bit back into the button state.
                        buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)
                    }

                    changedButtons = buttonState xor lastButtonState
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider!!.isCapturingActive) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider!!.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    val deltaX = inputCaptureProvider!!.getRelativeAxisX(event).toInt().toShort()
                    val deltaY = inputCaptureProvider!!.getRelativeAxisY(event).toInt().toShort()

                    if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                        if (prefConfig!!.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
                            conn!!.sendMouseMoveAsMousePosition(
                                deltaX,
                                deltaY,
                                streamView!!.width.toShort(),
                                streamView!!.height.toShort()
                            )
                        } else {
                            conn!!.sendMouseMove(deltaX, deltaY)
                        }
                    }
                } else if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    val device = event.device
                    if (device != null) {
                        val xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource)
                        val yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource)

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.min == 0f && yRange.min == 0f) {
                            val xMax = xRange.max.toInt()
                            val yMax = yRange.max.toInt()

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.Companion.MAX_VALUE && yMax <= Short.Companion.MAX_VALUE) {
                                conn!!.sendMousePosition(
                                    event.x.toInt().toShort(), event.y.toInt().toShort(),
                                    xMax.toShort(), yMax.toShort()
                                )
                            }
                        }
                    }
                } else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true
                } else if (view != null) {
                    // Otherwise send absolute position based on the view for SOURCE_CLASS_POINTER
                    updateMousePosition(view, event)
                }

                if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn!!.sendMouseHighResScroll(
                        (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort()
                    )
                    conn!!.sendMouseHighResHScroll(
                        (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort()
                    )
                }

                if ((changedButtons and MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                    } else {
                        conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                    } else {
                        conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                    } else {
                        conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                    }
                }

                if (prefConfig!!.mouseNavButtons) {
                    if ((changedButtons and MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_BACK) != 0) {
                            conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1)
                        } else {
                            conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1)
                        }
                    }

                    if ((changedButtons and MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) {
                            conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2)
                        } else {
                            conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
                        }
                    }
                }

                // Handle stylus presses
                if (event.pointerCount == 1 && event.actionIndex == 0) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.eventTime
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)

                            // Stylus is left click
                            conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.eventTime
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)

                            // Eraser is right click
                            conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.eventTime
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)

                            // Stylus is left click
                            conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.eventTime
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)

                            // Eraser is right click
                            conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    }
                }

                lastButtonState = buttonState
            } else {
                if (virtualController != null &&
                    (virtualController!!.controllerMode == VirtualController.ControllerMode.MoveButtons ||
                            virtualController!!.controllerMode == VirtualController.ControllerMode.ResizeButtons)
                ) {
                    // Ignore presses when the virtual controller is being configured
                    return true
                }

                // If this is the parent view, we'll offset our coordinates to appear as if they
                // are relative to the StreamView like our StreamView touch events are.
                var xOffset: Float
                var yOffset: Float
                if (view !== streamView && !prefConfig!!.touchscreenTrackpad) {
                    xOffset = -streamView!!.x
                    yOffset = -streamView!!.y
                } else {
                    xOffset = 0f
                    yOffset = 0f
                }

                val actionIndex = event.actionIndex

                val eventX = (event.getX(actionIndex) + xOffset).toInt()
                val eventY = (event.getY(actionIndex) + yOffset).toInt()

                // Special handling for 3 finger gesture
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                    event.pointerCount == 3
                ) {
                    // Three fingers down
                    threeFingerDownTime = event.eventTime

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (aTouchContext in touchContextMap) {
                        aTouchContext?.cancelTouch()
                    }

                    return true
                }

                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the software keyboard.
                /*if (!prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                    // If this host supports touch events and absolute touch is enabled,
                    // send it directly as a touch event.
                    return true;
                }*/
                val context = getTouchContext(actionIndex)
                if (context == null) {
                    return false
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                        for (touchContext in touchContextMap) {
                            touchContext?.setPointerCount(event.pointerCount)
                        }
                        context.touchDownEvent(eventX, eventY, event.eventTime, true)
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        if (event.pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.flags and MotionEvent.FLAG_CANCELED) == 0)
                        ) {
                            // All fingers up
                            if (event.eventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                                // This is a 3 finger tap to bring up the keyboard
                                toggleKeyboard()
                                return true
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                            context.cancelTouch()
                        } else {
                            context.touchUpEvent(eventX, eventY, event.eventTime)
                        }

                        for (touchContext in touchContextMap) {
                            touchContext?.setPointerCount(event.pointerCount - 1)
                        }
                        if (actionIndex == 0 && event.pointerCount > 1 && !context.isCancelled()) {
                            // The original secondary touch now becomes primary
                            context.touchDownEvent(
                                (event.getX(1) + xOffset).toInt(),
                                (event.getY(1) + yOffset).toInt(),
                                event.eventTime, false
                            )
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // ACTION_MOVE is special because it always has actionIndex == 0
                        // We'll call the move handlers for all indexes manually

                        // First process the historical events
                        for (i in 0 until event.historySize) {
                            // Use 'forEach' for more idiomatic iteration
                            touchContextMap.forEach { aTouchContextMap ->
                                // Safe call chaining for null safety and conciseness
                                aTouchContextMap?.takeIf { it.actionIndex < event.pointerCount }?.let {
                                    // 'it' now refers to the non-null 'aTouchContextMap'
                                    it.touchMoveEvent(
                                        (event.getHistoricalX(it.actionIndex, i) + xOffset).toInt(),
                                        (event.getHistoricalY(it.actionIndex, i) + yOffset).toInt(),
                                        event.getHistoricalEventTime(i)
                                    )
                                }
                            }
                        }

                        // Now process the current values
                        touchContextMap.forEach { aTouchContextMap ->
                            aTouchContextMap?.takeIf { it.actionIndex < event.pointerCount }?.let {
                                it.touchMoveEvent(
                                    (event.getX(it.actionIndex) + xOffset).toInt(),
                                    (event.getY(it.actionIndex) + yOffset).toInt(),
                                    event.eventTime
                                )
                            }
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> for (aTouchContext in touchContextMap) {
                        aTouchContext?.cancelTouch()
                        aTouchContext?.setPointerCount(0)
                    }

                    else -> return false
                }
            }

            // Handled a known source
            return true
        }

        // Unknown class
        return false
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event)
    }

    private fun updateMousePosition(touchedView: View?, event: MotionEvent) {
        // X and Y are already relative to the provided view object
        var eventX: Float
        var eventY: Float

        // For our StreamView itself, we can use the coordinates unmodified.
        if (touchedView === streamView) {
            eventX = event.getX(0)
            eventY = event.getY(0)
        } else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - streamView!!.x
            eventY = event.getY(0) - streamView!!.y
        }

        if (event.pointerCount == 1 && event.actionIndex == 0 &&
            (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_HOVER_MOVE -> if (event.eventTime - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                    sqrt((eventX - lastAbsTouchUpX).pow(2) + (eventY - lastAbsTouchUpY).pow(2)) <= STYLUS_UP_DEAD_ZONE_RADIUS
                ) {
                    // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                    return
                }

                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (event.eventTime - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                    sqrt(
                        (eventX - lastAbsTouchDownX).pow(2) + (eventY - lastAbsTouchDownY).pow(2)
                    ) <= STYLUS_DOWN_DEAD_ZONE_RADIUS
                ) {
                    // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                    return
                }
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = eventX.coerceIn(0.0f, streamView?.width?.toFloat() ?: 0.0f)
        eventY = eventY.coerceIn(0.0f, streamView?.height?.toFloat() ?: 0.0f)
        conn!!.sendMousePosition(
            eventX.toInt().toShort(),
            eventY.toInt().toShort(),
            streamView!!.width.toShort(),
            streamView!!.height.toShort()
        )
    }

    override fun onGenericMotion(view: View?, event: MotionEvent): Boolean {
        return handleMotionEvent(view, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            view.requestUnbufferedDispatch(event)
        }

        return handleMotionEvent(view, event)
    }

    override fun stageStarting(stage: String?) {
        runOnUiThread(object : Runnable {
            override fun run() {
                if (spinner != null) {
                    spinner!!.setMessage(resources.getString(R.string.conn_starting) + " " + stage)
                }
            }
        })
    }

    override fun stageComplete(stage: String?) {
    }

    private fun stopConnection() {
        if (connecting || connected) {
            connected = false
            connecting = false
            updatePipAutoEnter()

            controllerHandler!!.stop()

            // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this)

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            object : Thread() {
                override fun run() {
                    conn!!.stop()
                }
            }.start()
        }
    }

    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        val portTestResult =
            MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags)

        runOnUiThread(object : Runnable {
            override fun run() {
                if (spinner != null) {
                    spinner!!.dismiss()
                    spinner = null
                }

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true
                    LimeLog.severe("$stage failed: $errorCode")

                    // If video initialization failed and the surface is still valid, display extra information for the user
                    if (stage.contains("video") && streamView!!.holder.surface
                            .isValid
                    ) {
                        Toast.makeText(
                            this@Game,
                            resources.getText(R.string.video_decoder_init_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    var dialogText =
                        resources.getString(R.string.conn_error_msg) + " " + stage + " (error " + errorCode + ")"

                    if (portFlags != 0) {
                        dialogText += "\n\n" + resources.getString(R.string.check_ports_msg) + "\n" +
                                MoonBridge.stringifyPortFlags(portFlags, "\n")
                    }

                    if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                        dialogText += "\n\n" + resources.getString(R.string.nettest_text_blocked)
                    }

                    Dialog.displayDialog(
                        this@Game,
                        resources.getString(R.string.conn_error_title),
                        dialogText,
                        true
                    )
                }
            }
        })
    }

    override fun connectionTerminated(errorCode: Int) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        val portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode)
        val portTestResult =
            MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags)

        runOnUiThread(object : Runnable {
            override fun run() {
                // Let the display go to sleep now
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // Stop processing controller input
                controllerHandler!!.stop()

                // Ungrab input
                setInputGrabState(false)

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true
                    LimeLog.severe("Connection terminated: $errorCode")
                    stopConnection()

                    // Display the error dialog if it was an unexpected termination.
                    // Otherwise, just finish the activity immediately.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        var message: String?

                        if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                            // If we got a blocked result, that supersedes any other error message
                            message = resources.getString(R.string.nettest_text_blocked)
                        } else {
                            when (errorCode) {
                                MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> message =
                                    resources.getString(R.string.no_video_received_error)

                                MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> message =
                                    resources.getString(R.string.no_frame_received_error)

                                MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, MoonBridge.ML_ERROR_PROTECTED_CONTENT -> message =
                                    resources.getString(R.string.early_termination_error)

                                MoonBridge.ML_ERROR_FRAME_CONVERSION -> message =
                                    resources.getString(R.string.frame_conversion_error)

                                else -> {
                                    // We'll assume large errors are hex values
                                    var errorCodeString = if (abs(errorCode.toDouble()) > 1000) {
                                        Integer.toHexString(errorCode)
                                    } else {
                                        errorCode.toString()
                                    }
                                    message =
                                        resources.getString(R.string.conn_terminated_msg) + "\n\n" +
                                                resources.getString(R.string.error_code_prefix) + " " + errorCodeString
                                }
                            }
                        }

                        if (portFlags != 0) {
                            message += "\n\n" + resources.getString(R.string.check_ports_msg) + "\n" +
                                    MoonBridge.stringifyPortFlags(portFlags, "\n")
                        }

                        Dialog.displayDialog(
                            this@Game, resources.getString(R.string.conn_terminated_title),
                            message, true
                        )
                    } else {
                        finish()
                    }
                }
            }
        })
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        runOnUiThread(object : Runnable {
            override fun run() {
                if (prefConfig!!.disableWarnings) {
                    return
                }

                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                    if (prefConfig!!.bitrate > 5000) {
                        notificationOverlayView!!.text = resources.getString(R.string.slow_connection_msg)
                    } else {
                        notificationOverlayView!!.text = resources.getString(R.string.poor_connection_msg)
                    }

                    requestedNotificationOverlayVisibility = View.VISIBLE
                } else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                    requestedNotificationOverlayVisibility = View.GONE
                }

                if (!isHidingOverlays) {
                    notificationOverlayView!!.visibility = requestedNotificationOverlayVisibility
                }
            }
        })
    }

    override fun connectionStarted() {
        runOnUiThread(object : Runnable {
            override fun run() {
                if (spinner != null) {
                    spinner!!.dismiss()
                    spinner = null
                }

                connected = true
                connecting = false
                updatePipAutoEnter()

                // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                val h = Handler()
                h.postDelayed(object : Runnable {
                    override fun run() {
                        setInputGrabState(true)
                    }
                }, 500)

                // Keep the display on
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(this@Game)

                hideSystemUi(1000)
            }
        })

        // Report this shortcut being used (off the main thread to prevent ANRs)
        val computer = ComputerDetails()
        computer.name = pcName
        computer.uuid = this@Game.intent.getStringExtra(EXTRA_PC_UUID)
        val shortcutHelper = ShortcutHelper(this)
        shortcutHelper.reportComputerShortcutUsed(computer)
        if (appName != null) {
            // This may be null if launched from the "Resume Session" PC context menu item
            shortcutHelper.reportGameLaunched(computer, app)
        }
    }

    override fun displayMessage(message: String?) {
        runOnUiThread(object : Runnable {
            override fun run() {
                Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun displayTransientMessage(message: String?) {
        if (!prefConfig!!.disableWarnings) {
            runOnUiThread(object : Runnable {
                override fun run() {
                    Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        LimeLog.info(
            String.format(
                null as Locale?,
                "Rumble on gamepad %d: %04x %04x",
                controllerNumber,
                lowFreqMotor,
                highFreqMotor
            )
        )

        controllerHandler!!.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        LimeLog.info(
            String.format(
                null as Locale?,
                "Rumble on gamepad triggers %d: %04x %04x",
                controllerNumber,
                leftTrigger,
                rightTrigger
            )
        )

        controllerHandler!!.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        LimeLog.info("Display HDR mode: " + (if (enabled) "enabled" else "disabled"))
        decoderRenderer!!.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(
        controllerNumber: Short,
        motionType: Byte,
        reportRateHz: Short
    ) {
        controllerHandler!!.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        controllerHandler!!.handleSetControllerLED(controllerNumber, r, g, b)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        check(surfaceCreated) { "Surface changed before creation!" }

        if (!attemptedConnection) {
            attemptedConnection = true

            // Update GameManager state to indicate we're "loading" while connecting
            UiHelper.notifyStreamConnecting(this@Game)

            decoderRenderer!!.setRenderTarget(holder)
            conn!!.start(
                AndroidAudioRenderer(this@Game, prefConfig!!.enableAudioFx),
                decoderRenderer, this@Game
            )
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {

        surfaceCreated = true

        // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        var desiredFrameRate: Float = if (mayReduceRefreshRate() || desiredRefreshRate < prefConfig!!.fps) {
            prefConfig!!.fps.toFloat()
        } else {
            // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
            // frame rate evenly divides into, which ensures the lowest possible display latency.
            desiredRefreshRate
        }

        // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder.surface.setFrameRate(
                desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(
                desiredFrameRate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        check(surfaceCreated) { "Surface destroyed before creation!" }

        if (attemptedConnection) {
            // Let the decoder know immediately that the surface is gone
            decoderRenderer!!.prepareForStop()

            if (connected) {
                stopConnection()
            }
        }
    }

    override fun mouseMove(deltaX: Int, deltaY: Int) {
        conn!!.sendMouseMove(deltaX.toShort(), deltaY.toShort())
    }

    override fun mouseButtonEvent(buttonId: Int, down: Boolean) {
        var buttonIndex: Byte = when (buttonId) {
            EvdevListener.BUTTON_LEFT -> MouseButtonPacket.BUTTON_LEFT
            EvdevListener.BUTTON_MIDDLE -> MouseButtonPacket.BUTTON_MIDDLE
            EvdevListener.BUTTON_RIGHT -> MouseButtonPacket.BUTTON_RIGHT
            EvdevListener.BUTTON_X1 -> MouseButtonPacket.BUTTON_X1
            EvdevListener.BUTTON_X2 -> MouseButtonPacket.BUTTON_X2
            else -> {
                LimeLog.warning("Unhandled button: $buttonId")
                return
            }
        }

        if (down) {
            conn!!.sendMouseButtonDown(buttonIndex)
        } else {
            conn!!.sendMouseButtonUp(buttonIndex)
        }
    }

    override fun mouseVScroll(amount: Byte) {
        conn!!.sendMouseScroll(amount)
    }

    override fun mouseHScroll(amount: Byte) {
        conn!!.sendMouseHScroll(amount)
    }

    override fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
        val keyMap = keyboardTranslator!!.translate(keyCode.toInt(), -1)
        if (keyMap.toInt() != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode.toInt(), buttonDown)) {
                return
            }

            if (buttonDown) {
                conn!!.sendKeyboardInput(
                    keyMap,
                    KeyboardPacket.KEY_DOWN,
                    getModifierState(),
                    0.toByte()
                )
            } else {
                conn!!.sendKeyboardInput(
                    keyMap,
                    KeyboardPacket.KEY_UP,
                    getModifierState(),
                    0.toByte()
                )
            }
        }
    }

    override fun onSystemUiVisibilityChange(visibility: Int) {
        // Don't do anything if we're not connected
        if (!connected) {
            return
        }

        // This flag is set for all devices
        if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000)
        } else if ((visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000)
        }
    }

    override fun onPerfUpdate(text: String?) {
        runOnUiThread(object : Runnable {
            override fun run() {
                performanceOverlayView!!.text = text
            }
        })
    }

    override fun onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++
        updatePipAutoEnter()
    }

    override fun onUsbPermissionPromptCompleted() {
        suppressPipRefCount--
        updatePipAutoEnter()
    }

    override fun onKey(view: View?, keyCode: Int, keyEvent: KeyEvent): Boolean {
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> handleKeyMultiple(keyEvent)
            else -> false
        }
    }

    companion object {
        private const val REFERENCE_HORIZ_RES = 1280
        private const val REFERENCE_VERT_RES = 720

        private const val STYLUS_DOWN_DEAD_ZONE_DELAY = 100
        private const val STYLUS_DOWN_DEAD_ZONE_RADIUS = 20

        private const val STYLUS_UP_DEAD_ZONE_DELAY = 150
        private const val STYLUS_UP_DEAD_ZONE_RADIUS = 50

        private const val THREE_FINGER_TAP_THRESHOLD = 300

        const val EXTRA_HOST: String = "Host"
        const val EXTRA_PORT: String = "Port"
        const val EXTRA_HTTPS_PORT: String = "HttpsPort"
        const val EXTRA_APP_NAME: String = "AppName"
        const val EXTRA_APP_ID: String = "AppId"
        const val EXTRA_UNIQUEID: String = "UniqueId"
        const val EXTRA_PC_UUID: String = "UUID"
        const val EXTRA_PC_NAME: String = "PcName"
        const val EXTRA_APP_HDR: String = "HDR"
        const val EXTRA_SERVER_CERT: String = "ServerCert"

        private fun normalizeValueInRange(value: Float, range: MotionRange): Float {
            return (value - range.min) / range.range
        }

        private fun getPressureOrDistance(event: MotionEvent, pointerIndex: Int): Float {
            val dev = event.device
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
                    // Hover events report distance
                    if (dev != null) {
                        val distanceRange =
                            dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source)
                        if (distanceRange != null) {
                            return normalizeValueInRange(
                                event.getAxisValue(
                                    MotionEvent.AXIS_DISTANCE,
                                    pointerIndex
                                ), distanceRange
                            )
                        }
                    }
                    return 0.0f
                }

                else ->                 // Other events report pressure
                    return event.getPressure(pointerIndex)
            }
        }

        private fun getRotationDegrees(event: MotionEvent, pointerIndex: Int): Short {
            val dev = event.device
            if (dev != null) {
                if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) != null) {
                    var rotationDegrees =
                        Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).toInt()
                            .toShort()
                    if (rotationDegrees < 0) {
                        rotationDegrees = (rotationDegrees + 360).toShort()
                    }
                    return rotationDegrees
                }
            }
            return MoonBridge.LI_ROT_UNKNOWN
        }

        private fun polarToCartesian(r: Float, theta: Float): FloatArray {
            return floatArrayOf(
                (r * cos(theta.toDouble())).toFloat(),
                (r * sin(theta.toDouble())).toFloat()
            )
        }

        private fun cartesianToR(point: FloatArray): Float {
            return sqrt(point[0].pow(2) + point[1].pow(2))
        }

        private fun convertToolTypeToStylusToolType(event: MotionEvent, pointerIndex: Int): Byte {
            return when (event.getToolType(pointerIndex)) {
                MotionEvent.TOOL_TYPE_ERASER -> MoonBridge.LI_TOOL_TYPE_ERASER
                MotionEvent.TOOL_TYPE_STYLUS -> MoonBridge.LI_TOOL_TYPE_PEN
                else -> MoonBridge.LI_TOOL_TYPE_UNKNOWN
            }
        }
    }
}
