package com.example.rawrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.rotate
import android.content.pm.ActivityInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.rawrecorder.ui.theme.RawRecorderTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

data class ResolutionOption(
    val cropWidth: Int,
    val cropHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val aspectName: String
)

data class CameraLensInfo(
    val physicalId: String,
    val logicalId: String,
    val name: String,
    val focalLength: Float,
    val activeArraySize: Rect,
    val resolutions: List<ResolutionOption>,
    val supportedFps: List<Int>,
    val cfaPattern: Int
)

enum class ActiveMenu { NONE, LENSES, RESOLUTIONS, FPS, STABILITY }
enum class StabilityMode { OFF, OIS, EIS, OIS_EIS }

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var rawSpoolerEngine: RawSpoolerEngine? = null
    private lateinit var prefs: SharedPreferences

    private var previewSurface: Surface? = null
    private var viewWidth = 0
    private var viewHeight = 0

    private var hasCameraPermission by mutableStateOf(false)
    private var isRecording by mutableStateOf(false)

    private var availableLenses by mutableStateOf<List<CameraLensInfo>>(emptyList())
    private var selectedLens by mutableStateOf<CameraLensInfo?>(null)
    private var selectedResolution by mutableStateOf<ResolutionOption?>(null)
    private var availableFpsForRes by mutableStateOf<List<Int>>(emptyList())
    private var selectedFps by mutableStateOf(30) // Set default FPS to 30 on startup

    private var isPhotoMode by mutableStateOf(false)

    private var isAutoExposure by mutableStateOf(true)
    private var shutterSpeedNanos by mutableStateOf(41666666L)
    private var isoValue by mutableStateOf(400)
    private var evCompensation by mutableStateOf(0)

    private var isAutoWb by mutableStateOf(true)
    private var kelvinValue by mutableStateOf(5500f)
    private var tintValue by mutableStateOf(0f)

    private var isAutoFocus by mutableStateOf(true)
    private var focusDistance by mutableStateOf(0f)
    
    private var stabilityMode by mutableStateOf(StabilityMode.OFF)
    
    private var activeMenu by mutableStateOf(ActiveMenu.NONE)

    private var recordingTimeSeconds by mutableStateOf(0)
    private var recordedFileBytes by mutableStateOf(0L)
    private var droppedFrames by mutableStateOf(0)
    private var captureName by mutableStateOf("rawrecorder")
    private var usePrivateStorage by mutableStateOf(false)
    private var useGoogleMetadata by mutableStateOf(true)

    // Dynamic metadata tracking values
    private var lastPostRawBoost = 100
    private var lastAperture = 1.8f
    private var lastFocalLength = 4.5f
    private var lastNoiseProfile: Array<android.util.Pair<Double, Double>>? = null

    // Touch Lock States
    private var is3ALocked by mutableStateOf(false)
    private var activeMeteringRect: MeteringRectangle? = null
    private var tapX by mutableStateOf(-1f)
    private var tapY by mutableStateOf(-1f)
    private var showTapCircle by mutableStateOf(false)
    private var tapCircleTimerJob: Job? = null

    // EV Adjust States
    private var minEvCompensation by mutableStateOf(-24)
    private var maxEvCompensation by mutableStateOf(24)
    private var aeStepSize by mutableStateOf(1f / 6f)
    private var evAccumulator = 0f
    private var showEvFeedback by mutableStateOf(false)
    private var evFeedbackTimerJob: Job? = null

    // Gesture Bottom Bar States
    private var activeDragMenu by mutableStateOf("")
    private var dragResetJob: Job? = null

    private var surfaceHolder: SurfaceHolder? = null
    private var appRotationDegrees by mutableStateOf(0)
    private var previewSizeState by mutableStateOf<Size?>(null)
    private var minIso by mutableStateOf(50)
    private var maxIso by mutableStateOf(3200)
    private var minShutterNanos by mutableStateOf(10000L)
    private var maxShutterNanos by mutableStateOf(1000000000L)
    private var showHudMonitor by mutableStateOf(false)
    private var hudFeedbackJob: Job? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
            if (hasCameraPermission) {
                initializeCameras()
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Exclusive fullscreen immersive mode
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        
        prefs = getSharedPreferences("RawRecorderSettings", Context.MODE_PRIVATE)
        captureName = prefs.getString("captureName", "rawrecorder") ?: "rawrecorder"
        usePrivateStorage = prefs.getBoolean("usePrivateStorage", false)
        useGoogleMetadata = true
        appRotationDegrees = prefs.getInt("appRotationDegrees", 0)
        isAutoExposure = prefs.getBoolean("isAutoExposure", true)
        shutterSpeedNanos = prefs.getLong("shutterSpeedNanos", 41666666L)
        isoValue = prefs.getInt("isoValue", 400)
        isAutoWb = prefs.getBoolean("isAutoWb", true)
        kelvinValue = prefs.getFloat("kelvinValue", 5500f)
        tintValue = prefs.getFloat("tintValue", 0f)
        isAutoFocus = prefs.getBoolean("isAutoFocus", true)
        focusDistance = prefs.getFloat("focusDistance", 0f)
        isPhotoMode = prefs.getBoolean("isPhotoMode", false)
        val savedStab = prefs.getString("stabilityMode", "OFF") ?: "OFF"
        stabilityMode = try { StabilityMode.valueOf(savedStab) } catch (e: Exception) { StabilityMode.OFF }

        // Lock screen orientation to saved preference (ignoring system rotation)
        requestedOrientation = if (appRotationDegrees == 90) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            hasCameraPermission = true
        }

        setContent {
            RawRecorderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    if (hasCameraPermission && selectedLens != null) {
                        ProCameraUI()
                    } else {
                        Box(contentAlignment = Alignment.Center) { Text("Initializing...", color = Color.White) }
                    }
                }
            }
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putString("captureName", captureName)
            putBoolean("usePrivateStorage", usePrivateStorage)
            putBoolean("useGoogleMetadata", useGoogleMetadata)
            putInt("appRotationDegrees", appRotationDegrees)
            putBoolean("isAutoExposure", isAutoExposure)
            putLong("shutterSpeedNanos", shutterSpeedNanos)
            putInt("isoValue", isoValue)
            putBoolean("isAutoWb", isAutoWb)
            putFloat("kelvinValue", kelvinValue)
            putFloat("tintValue", tintValue)
            putBoolean("isAutoFocus", isAutoFocus)
            putFloat("focusDistance", focusDistance)
            putBoolean("isPhotoMode", isPhotoMode)
            putString("stabilityMode", stabilityMode.name)
            putString("selectedLensId", selectedLens?.physicalId)
            putInt("selectedResolutionWidth", selectedResolution?.cropWidth ?: 0)
            putInt("selectedResolutionHeight", selectedResolution?.cropHeight ?: 0)
            putString("selectedResolutionAspect", selectedResolution?.aspectName ?: "4:3")
            putInt("selectedFps", selectedFps)
            apply()
        }
        triggerHudFeedback()
    }

    private fun triggerHudFeedback() {
        showHudMonitor = true
        hudFeedbackJob?.cancel()
        hudFeedbackJob = lifecycleScope.launch {
            delay(3000)
            showHudMonitor = false
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        closeCamera()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission) {
            initializeCameras()
            startCamera()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawSpoolerEngine?.stopRecording()
        rawSpoolerEngine = null
        isRecording = false
    }

    private fun initializeCameras() {
        val lenses = mutableListOf<CameraLensInfo>()
        val RAW_FORMAT = ImageFormat.RAW10 // CRITICAL: MIPI RAW10 for 60% bandwidth reduction

        for (logicalId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(logicalId)
            val physicalIds = chars.physicalCameraIds
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            if (physicalIds.isNotEmpty()) {
                // It is a logical multi-camera, check its physical cameras
                for (pid in physicalIds) {
                    val pChars = cameraManager.getCameraCharacteristics(pid)
                    val rawSizes = pChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(RAW_FORMAT)?.toList() ?: emptyList()
                    if (rawSizes.isNotEmpty()) {
                        val fl = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                        val pFacing = pChars.get(CameraCharacteristics.LENS_FACING)
                        val name = when {
                            pFacing == CameraMetadata.LENS_FACING_FRONT -> "F"
                            fl < 4.0f -> "UW"
                            fl < 6.0f -> "W"
                            else -> "T"
                        }
                        val activeArray = pChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0,0,0,0)
                        val cfa = pChars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 2
                        
                        val fpsRanges = pChars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                        val fpsList = fpsRanges.map { it.upper }.distinct().sorted()
                        
                        val resOptions = mutableListOf<ResolutionOption>()
                        for (size in rawSizes) {
                            resOptions.add(ResolutionOption(size.width, size.height, size.width, size.height, "4:3"))
                            resOptions.add(ResolutionOption(size.width, size.width * 9 / 16, size.width, size.height, "16:9"))
                        }
                        lenses.add(CameraLensInfo(pid, logicalId, name, fl, activeArray, resOptions, fpsList, cfa))
                    }
                }
            } else {
                // Standalone camera
                val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(RAW_FORMAT)?.toList() ?: emptyList()
                if (rawSizes.isNotEmpty()) {
                    val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                    val name = when {
                        facing == CameraMetadata.LENS_FACING_FRONT -> "F"
                        fl < 4.0f -> "UW"
                        fl < 6.0f -> "W"
                        else -> "T"
                    }
                    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0,0,0,0)
                    val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 2
                    
                    val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                    val fpsList = fpsRanges.map { it.upper }.distinct().sorted()
                    
                    val resOptions = mutableListOf<ResolutionOption>()
                    for (size in rawSizes) {
                        resOptions.add(ResolutionOption(size.width, size.height, size.width, size.height, "4:3"))
                        resOptions.add(ResolutionOption(size.width, size.width * 9 / 16, size.width, size.height, "16:9"))
                    }
                    lenses.add(CameraLensInfo(logicalId, logicalId, name, fl, activeArray, resOptions, fpsList, cfa))
                }
            }
        }

        // Fallback if no lenses listed
        if (lenses.isEmpty()) {
            val logicalId = cameraManager.cameraIdList.firstOrNull() ?: return
            val chars = cameraManager.getCameraCharacteristics(logicalId)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0,0,0,0)
            val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 2
            val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(RAW_FORMAT)?.toList() ?: emptyList()
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val fpsList = fpsRanges.map { it.upper }.distinct().sorted()
            val resOptions = mutableListOf<ResolutionOption>()
            for (size in rawSizes) {
                resOptions.add(ResolutionOption(size.width, size.height, size.width, size.height, "4:3"))
                resOptions.add(ResolutionOption(size.width, size.width * 9 / 16, size.width, size.height, "16:9"))
            }
            if (resOptions.isEmpty()) {
                resOptions.add(ResolutionOption(activeArray.width(), activeArray.height(), activeArray.width(), activeArray.height(), "4:3"))
                resOptions.add(ResolutionOption(activeArray.width(), activeArray.width() * 9 / 16, activeArray.width(), activeArray.height(), "16:9"))
            }
            lenses.add(CameraLensInfo(logicalId, logicalId, "W", 0f, activeArray, resOptions, fpsList, cfa))
        }

        // Deduplicate lenses by physicalId and sort: UW, W, T, F
        availableLenses = lenses.distinctBy { it.physicalId }.sortedBy { 
            when (it.name) {
                "UW" -> 1
                "W" -> 2
                "T" -> 3
                "F" -> 4
                else -> 5
            }
        }
        
        val savedLensId = prefs.getString("selectedLensId", null)
        val savedLens = availableLenses.firstOrNull { it.physicalId == savedLensId }
            ?: availableLenses.firstOrNull { it.name == "W" }
            ?: availableLenses.firstOrNull()
        selectedLens = savedLens
        
        val savedWidth = prefs.getInt("selectedResolutionWidth", 0)
        val savedHeight = prefs.getInt("selectedResolutionHeight", 0)
        val savedAspect = prefs.getString("selectedResolutionAspect", "4:3")
        val savedRes = selectedLens?.resolutions?.firstOrNull { it.cropWidth == savedWidth && it.cropHeight == savedHeight && it.aspectName == savedAspect }
            ?: selectedLens?.resolutions?.firstOrNull()
        selectedResolution = savedRes
        
        val savedFpsVal = prefs.getInt("selectedFps", 30)
        selectedFps = savedFpsVal
        updateFpsLimits()
    }

    private fun updateFpsLimits() {
        val lens = selectedLens ?: return
        availableFpsForRes = lens.supportedFps
        if (!availableFpsForRes.contains(selectedFps)) {
            selectedFps = availableFpsForRes.lastOrNull() ?: 30
        }
    }

    private fun getDeviceRotationDegrees(): Int {
        return (90 - appRotationDegrees + 360) % 360
    }

    private fun getDngOrientation(): Int {
        val lens = selectedLens ?: return 1
        val pChars = cameraManager.getCameraCharacteristics(lens.physicalId)
        val sensorOrientation = pChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deviceRotationDegrees = getDeviceRotationDegrees()
        val relativeRotation = (sensorOrientation - deviceRotationDegrees + 360) % 360
        return when (relativeRotation) {
            0 -> 1
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val lens = selectedLens ?: return
        val targetLogicalId = lens.logicalId
        if (cameraDevice != null) {
            if (cameraDevice!!.id == targetLogicalId) return // Already open
            closeCamera()
        }
        cameraManager.openCamera(targetLogicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                if (previewSurface != null) createSession()
            }
            override fun onDisconnected(camera: CameraDevice) { 
                camera.close() 
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) { 
                camera.close() 
                cameraDevice = null
            }
        }, null)
    }

    private fun chooseOptimalPreviewSize(pChars: CameraCharacteristics, targetAspect: Float): Size {
        val map = pChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceHolder::class.java) ?: return Size(1920, 1080)
        
        val targetTolerance = 0.05
        val matchingSizes = sizes.filter { size ->
            val aspect = size.width.toFloat() / size.height.toFloat()
            Math.abs(aspect - targetAspect) < targetTolerance
        }
        
        // Filter sizes that are not excessively large (cap preview size to 1920 width / 1080 height)
        val reasonableSizes = matchingSizes.filter { it.width <= 1920 && it.height <= 1080 }
        
        return if (reasonableSizes.isNotEmpty()) {
            reasonableSizes.maxByOrNull { it.width * it.height }!!
        } else if (matchingSizes.isNotEmpty()) {
            // Fallback to the smallest matching size if all are larger than 1920x1080
            matchingSizes.minByOrNull { it.width * it.height }!!
        } else {
            // Fallback to any reasonable size
            val fallbackReasonable = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            if (fallbackReasonable.isNotEmpty()) {
                fallbackReasonable.maxByOrNull { it.width * it.height }!!
            } else {
                sizes.minByOrNull { it.width * it.height }!!
            }
        }
    }

    private fun createSession() {
        val lens = selectedLens ?: return
        val res = selectedResolution ?: return
        val pChars = cameraManager.getCameraCharacteristics(lens.physicalId)
        
        val targetAspect = res.cropWidth.toFloat() / res.cropHeight.toFloat()
        val previewSize = chooseOptimalPreviewSize(pChars, targetAspect)
        previewSizeState = previewSize
        
        val holder = surfaceHolder ?: return
        val surfaceSizeMatches = (viewWidth == previewSize.width && viewHeight == previewSize.height)
        
        if (!surfaceSizeMatches) {
            Log.d("CAMERA_DEBUG", "Resizing surface to ${previewSize.width}x${previewSize.height}")
            holder.setFixedSize(previewSize.width, previewSize.height)
            // Wait for surfaceChanged callback to trigger createSession() with correct size
            return
        }
        
        Log.d("CAMERA_DEBUG", "Creating session with surface size ${viewWidth}x${viewHeight}")
        
        captureSession?.close()
        imageReader?.close()
        rawSpoolerEngine?.stopRecording()
        isRecording = false
        
        val cm1 = pChars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val cm2 = pChars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        val fm1 = pChars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
        val fm2 = pChars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        val ill1 = pChars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 17
        val ill2 = pChars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 21
        
        val blPattern = pChars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevelArray = IntArray(4)
        blPattern?.copyTo(blackLevelArray, 0)
        
        val whiteLevel = pChars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        
        val preCorrectionRect = pChars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: pChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeRect = pChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val cropLeft = if (preCorrectionRect != null && activeRect != null) activeRect.left - preCorrectionRect.left else 0
        val cropTop = if (preCorrectionRect != null && activeRect != null) activeRect.top - preCorrectionRect.top else 0
        val cropWidth = activeRect?.width() ?: res.cropWidth
        val cropHeight = activeRect?.height() ?: res.cropHeight

        // Read exposure values dynamically
        val aeRange = pChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        minEvCompensation = aeRange?.lower ?: -24
        maxEvCompensation = aeRange?.upper ?: 24
        evCompensation = evCompensation.coerceIn(minEvCompensation, maxEvCompensation)

        val stepRational = pChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        aeStepSize = stepRational?.toFloat() ?: (1f / 6f)

        val isoRange = pChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null) {
            minIso = isoRange.lower
            maxIso = isoRange.upper
        }
        val shutterRange = pChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (shutterRange != null) {
            minShutterNanos = shutterRange.lower
            maxShutterNanos = shutterRange.upper
        }

        fun toIntArray(t: android.hardware.camera2.params.ColorSpaceTransform?): IntArray {
            val arr = IntArray(18)
            if (t != null) {
                for (i in 0 until 9) {
                    arr[i*2] = t.getElement(i % 3, i / 3).numerator
                    arr[i*2+1] = t.getElement(i % 3, i / 3).denominator
                }
            } else {
                for (i in 0 until 18) arr[i] = if (i % 2 == 1) 10000 else 10000
            }
            return arr
        }
        
        val fallbackApertures = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val defaultAperture = fallbackApertures?.firstOrNull() ?: 1.8f
        val currentAperture = if (lastAperture > 0f) lastAperture else defaultAperture

        val fallbackFocalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val defaultFocalLength = fallbackFocalLengths?.firstOrNull() ?: 4.5f
        val currentFocalLength = if (lastFocalLength > 0f) lastFocalLength else defaultFocalLength

        val sensorType = when (lens.name) {
            "UW" -> 1
            "T" -> 2
            "F" -> 3
            else -> 0
        }

        val dynamicCm1: IntArray
        val dynamicCm2: IntArray
        val dynamicFm1: IntArray
        val dynamicFm2: IntArray
        val ill1Val: Int
        val ill2Val: Int
        val cal1 = intArrayOf(
            1, 1, 0, 1, 0, 1,
            0, 1, 1, 1, 0, 1,
            0, 1, 0, 1, 1, 1
        )
        val cal2 = intArrayOf(
            1, 1, 0, 1, 0, 1,
            0, 1, 1, 1, 0, 1,
            0, 1, 0, 1, 1, 1
        )
        val baselineExpNum: Int
        val baselineExpDen: Int
        val noiseProfileArray = FloatArray(8)

        when (sensorType) {
            1 -> { // Ultrawide
                dynamicCm1 = intArrayOf(14178, 10000, -8720, 10000, 688, 10000, -2895, 10000, 11364, 10000, 1730, 10000, 110, 10000, 814, 10000, 5508, 10000)
                dynamicCm2 = intArrayOf(11835, 10000, -5488, 10000, -1032, 10000, -3371, 10000, 11891, 10000, 1648, 10000, -194, 10000, 1235, 10000, 4748, 10000)
                dynamicFm1 = intArrayOf(3777, 10000, 4906, 10000, 960, 10000, 1585, 10000, 8136, 10000, 278, 10000, 459, 10000, 16, 10000, 7775, 10000)
                dynamicFm2 = intArrayOf(3806, 10000, 4501, 10000, 1336, 10000, 1773, 10000, 7842, 10000, 385, 10000, 652, 10000, 6, 10000, 7593, 10000)
                ill1Val = 17
                ill2Val = 21
                baselineExpNum = 12
                baselineExpDen = 100
                floatArrayOf(
                    0.0004338437f, 4.3466407e-6f,
                    0.0002172339f, 2.2114516e-6f,
                    0.0002172339f, 2.2114516e-6f,
                    0.0004287199f, 4.3406994e-6f
                ).copyInto(noiseProfileArray)
            }
            2 -> { // Telephoto
                dynamicCm1 = intArrayOf(12163, 10000, -5088, 10000, -692, 10000, -2296, 10000, 10998, 10000, 1473, 10000, 211, 10000, 1016, 10000, 4655, 10000)
                dynamicCm2 = intArrayOf(8380, 10000, -1926, 10000, -623, 10000, -4094, 10000, 12822, 10000, 1364, 10000, -1193, 10000, 2655, 10000, 3949, 10000)
                dynamicFm1 = intArrayOf(4222, 10000, 4040, 10000, 1381, 10000, 1880, 10000, 7702, 10000, 418, 10000, 571, 10000, 5, 10000, 7675, 10000)
                dynamicFm2 = intArrayOf(5208, 10000, 3320, 10000, 1116, 10000, 2917, 10000, 6733, 10000, 350, 10000, 1702, 10000, 13, 10000, 6536, 10000)
                ill1Val = 17
                ill2Val = 21
                baselineExpNum = 267
                baselineExpDen = 100
                floatArrayOf(
                    0.00017274475f, 6.8975623e-7f,
                    8.3773375e-5f, 3.2082875e-7f,
                    8.3773375e-5f, 3.2082875e-7f,
                    0.0001556827f, 5.8859496e-7f
                ).copyInto(noiseProfileArray)
            }
            3 -> { // Front
                dynamicCm1 = intArrayOf(20174, 10000, -5979, 10000, -11229, 10000, -19432, 10000, 43771, 10000, -21631, 10000, -2889, 10000, 5882, 10000, 8368, 10000)
                dynamicCm2 = intArrayOf(12128, 10000, -5577, 10000, -1067, 10000, -3077, 10000, 11648, 10000, 1599, 10000, -121, 10000, 1501, 10000, 5141, 10000)
                dynamicFm1 = intArrayOf(5917, 10000, -885, 10000, 4611, 10000, 2729, 10000, 2453, 10000, 4819, 10000, 485, 10000, -4309, 10000, 12075, 10000)
                dynamicFm2 = intArrayOf(3638, 10000, 4646, 10000, 1359, 10000, 1621, 10000, 7989, 10000, 390, 10000, 543, 10000, 32, 10000, 7676, 10000)
                ill1Val = 17
                ill2Val = 21
                baselineExpNum = 0
                baselineExpDen = 100
                floatArrayOf(
                    2.0539945e-5f, 2.1160035e-7f,
                    1.4857906e-5f, 1.2181445e-7f,
                    1.4857906e-5f, 1.2181445e-7f,
                    2.0451544e-5f, 2.11745e-7f
                ).copyInto(noiseProfileArray)
            }
            else -> { // Main
                dynamicCm1 = intArrayOf(11234, 10000, -5774, 10000, 83, 10000, -3535, 10000, 12410, 10000, 1210, 10000, -303, 10000, 2176, 10000, 5922, 10000)
                dynamicCm2 = intArrayOf(10662, 10000, -4641, 10000, -954, 10000, -3284, 10000, 11970, 10000, 1451, 10000, -170, 10000, 1989, 10000, 5182, 10000)
                dynamicFm1 = intArrayOf(4078, 10000, 4619, 10000, 946, 10000, 2358, 10000, 7358, 10000, 284, 10000, 1183, 10000, 4, 10000, 7063, 10000)
                dynamicFm2 = intArrayOf(3666, 10000, 4641, 10000, 1335, 10000, 1639, 10000, 7979, 10000, 383, 10000, 477, 10000, 42, 10000, 7733, 10000)
                ill1Val = 17
                ill2Val = 21
                baselineExpNum = 5
                baselineExpDen = 100
                floatArrayOf(
                    2.0539945e-5f, 2.1160035e-7f,
                    1.4857906e-5f, 1.2181445e-7f,
                    1.4857906e-5f, 1.2181445e-7f,
                    2.0451544e-5f, 2.11745e-7f
                ).copyInto(noiseProfileArray)
            }
        }

        val lensIntrinsic = pChars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: floatArrayOf(0f, 0f, 0f, 0f, 0f)

        val lensDistortion = FloatArray(5)
        val ld = pChars.get(CameraCharacteristics.LENS_DISTORTION)
        if (ld != null && ld.size >= 5) {
            ld.copyInto(lensDistortion, 0, 0, 5)
        } else {
            val lrd = pChars.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
            if (lrd != null && lrd.size >= 6) {
                lensDistortion[0] = lrd[0]
                lensDistortion[1] = lrd[1]
                lensDistortion[2] = lrd[2]
                lensDistortion[3] = lrd[4]
                lensDistortion[4] = lrd[5]
            }
        }

        val deviceMake = android.os.Build.MANUFACTURER ?: "Google"
        val deviceModel = android.os.Build.MODEL ?: "Pixel"

        val preWidth = preCorrectionRect?.width() ?: res.cropWidth
        val preHeight = preCorrectionRect?.height() ?: res.cropHeight

        rawSpoolerEngine = RawSpoolerEngine(
            res.cropWidth, res.cropHeight, res.sourceHeight, lens.cfaPattern, 
            dynamicCm1, dynamicCm2, dynamicFm1, dynamicFm2, 
            ill1Val, ill2Val,
            blackLevelArray, whiteLevel, cropLeft, cropTop, cropWidth, cropHeight,
            sensorType,
            cal1, cal2,
            baselineExpNum,
            baselineExpDen,
            noiseProfileArray,
            lensIntrinsic,
            lensDistortion,
            currentAperture,
            currentFocalLength,
            deviceMake,
            deviceModel,
            preWidth,
            preHeight
        )
        rawSpoolerEngine?.prepareEnginePipeline()

        imageReader = ImageReader.newInstance(res.sourceWidth, res.sourceHeight, ImageFormat.RAW10, 15)
        imageReader?.setOnImageAvailableListener(rawSpoolerEngine?.imageAvailableListener, rawSpoolerEngine?.getEngineHandler())
        
        val rawOutputConfig = OutputConfiguration(imageReader!!.surface)
        if (lens.physicalId != lens.logicalId) {
            rawOutputConfig.setPhysicalCameraId(lens.physicalId)
        }
        
        val previewOutputConfig = OutputConfiguration(previewSurface!!)
        if (lens.physicalId != lens.logicalId) {
            previewOutputConfig.setPhysicalCameraId(lens.physicalId)
        }
        
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(rawOutputConfig, previewOutputConfig),
            ContextCompat.getMainExecutor(this@MainActivity),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updateRepeatingRequest()
                    triggerHudFeedback()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }
        )
        cameraDevice?.createCaptureSession(sessionConfig)
    }

    private fun switchLens(lens: CameraLensInfo) {
        selectedLens = lens
        selectedResolution = lens.resolutions.firstOrNull()
        updateFpsLimits()
        
        val targetLogicalId = lens.logicalId
        if (cameraDevice != null && cameraDevice!!.id == targetLogicalId) {
            createSession()
        } else {
            startCamera()
        }
        saveSettings()
    }

    private fun updateRepeatingRequest(focusRect: MeteringRectangle? = null) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(previewSurface!!)
            if (isRecording) builder.addTarget(imageReader!!.surface)
            
            val cropRegion = getScalerCropRegion()
            if (cropRegion.width() > 0 && cropRegion.height() > 0) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            }
            
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(selectedFps, selectedFps))
            
            if (is3ALocked) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                activeMeteringRect?.let {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
                }
            } else {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                
                if (isAutoExposure) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evCompensation)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedNanos)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
                }
                
                if (isAutoWb) {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                } else {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(kelvinValue, tintValue))
                }
                
                if (focusRect != null) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    
                    when (stabilityMode) {
                        StabilityMode.OFF -> {
                            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        }
                        StabilityMode.OIS -> {
                            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        }
                        StabilityMode.EIS -> {
                            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        }
                        StabilityMode.OIS_EIS -> {
                            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        }
                    }
                    
                    session.setRepeatingRequest(builder.build(), captureCallback, null)
                    
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    session.capture(builder.build(), captureCallback, null)
                    
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    return
                } else {
                    val currentRect = activeMeteringRect
                    if (currentRect != null) {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(currentRect))
                        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(currentRect))
                    } else {
                        if (isAutoFocus) {
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        } else {
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                        }
                    }
                }
            }
            
            when (stabilityMode) {
                StabilityMode.OFF -> {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
                StabilityMode.OIS -> {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
                StabilityMode.EIS -> {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
                StabilityMode.OIS_EIS -> {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
            }

            session.setRepeatingRequest(builder.build(), captureCallback, null)
        } catch (e: Exception) {
            Log.e("CAMERA_DEBUG", "Repeating Request Error: ${e.message}")
        }
    }

    private fun getScalerCropRegion(): Rect {
        val lens = selectedLens ?: return Rect(0,0,0,0)
        val res = selectedResolution ?: return Rect(0,0,0,0)
        val activeArray = lens.activeArraySize
        
        if (res.aspectName == "16:9") {
            val cropHeight = activeArray.width() * 9 / 16
            val cropTop = ((activeArray.height() - cropHeight) / 2) and -2
            return Rect(activeArray.left, activeArray.top + cropTop, activeArray.right, activeArray.top + cropTop + cropHeight)
        }
        return activeArray
    }

    fun triggerSinglePhotoCapture() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(previewSurface!!)
            builder.addTarget(imageReader!!.surface)

            val cropRegion = getScalerCropRegion()
            if (cropRegion.width() > 0 && cropRegion.height() > 0) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            }

            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
            )
            
            if (isAutoExposure) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evCompensation)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedNanos)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
            }
            
            if (isAutoWb) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(kelvinValue, tintValue))
            }
            
            if (isAutoFocus) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
            
            session.capture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e("CAMERA_DEBUG", "Single still capture error: ${e.message}")
        }
    }

    private fun kelvinToRggb(kelvin: Float, tint: Float): RggbChannelVector {
        val t = (kelvin - 2000f) / (10000f - 2000f)
        return RggbChannelVector(1.0f + (t * 2.0f), 1.0f + tint, 1.0f + tint, 3.5f - (t * 2.5f))
    }

    private fun formatShutterSpeed(nanos: Long): String {
        if (nanos >= 1000000000L) {
            val secs = nanos.toFloat() / 1e9f
            return if (secs % 1f == 0f) "${secs.toInt()}s" else String.format("%.1fs", secs)
        } else {
            val denom = Math.round(1e9 / nanos)
            return "1/$denom"
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (gains != null) rawSpoolerEngine?.updateLiveAwbGains(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            val expTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: shutterSpeedNanos
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: isoValue
            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDistance
            rawSpoolerEngine?.updateLiveCaptureParameters(expTime, iso, fd)
            
            val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            if (boost != null) lastPostRawBoost = boost
            val aperture = result.get(CaptureResult.LENS_APERTURE)
            if (aperture != null) lastAperture = aperture
            val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            if (focalLength != null) lastFocalLength = focalLength
            val noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
            if (noise != null) lastNoiseProfile = noise
        }
    }

    private fun handleTouchToFocus(x: Float, y: Float, viewWidthPx: Float, viewHeightPx: Float) {
        val lens = selectedLens ?: return
        val pChars = cameraManager.getCameraCharacteristics(lens.physicalId)
        val sensorOrientation = pChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val cropRegion = getScalerCropRegion()
        
        // 1. Calculate normalized touch coordinates (0f..1f) relative to the visible preview on screen
        val sensorAspect = cropRegion.width().toFloat() / max(1, cropRegion.height()).toFloat()
        
        val deviceRotationDegrees = getDeviceRotationDegrees()
        val relativeRotation = (sensorOrientation - deviceRotationDegrees + 360) % 360
        val isRotated = (relativeRotation == 90 || relativeRotation == 270)
        
        val displayAspect = if (isRotated) 1f / sensorAspect else sensorAspect
        val viewAspect = viewWidthPx / max(1f, viewHeightPx)
        
        var activeWidth = viewWidthPx
        var activeHeight = viewHeightPx
        var xOffset = 0f
        var yOffset = 0f
        
        if (viewAspect > displayAspect) {
            activeWidth = viewHeightPx * displayAspect
            xOffset = (viewWidthPx - activeWidth) / 2f
        } else {
            activeHeight = viewWidthPx / displayAspect
            yOffset = (viewHeightPx - activeHeight) / 2f
        }
        
        val nx = ((x - xOffset) / activeWidth).coerceIn(0f, 1f)
        val ny = ((y - yOffset) / activeHeight).coerceIn(0f, 1f)
        
        // 2. Map normalized touch coordinates to the sensor crop region based on rotation
        var rx = nx
        var ry = ny
        when (relativeRotation) {
            90 -> {
                rx = ny
                ry = 1.0f - nx
            }
            180 -> {
                rx = 1.0f - nx
                ry = 1.0f - ny
            }
            270 -> {
                rx = 1.0f - ny
                ry = nx
            }
        }
        
        // 3. Map rx/ry to absolute sensor coordinates in cropRegion
        val sensorX = cropRegion.left + (rx * cropRegion.width()).toInt()
        val sensorY = cropRegion.top + (ry * cropRegion.height()).toInt()
        val finalSensorX = sensorX.coerceIn(cropRegion.left, cropRegion.right)
        val finalSensorY = sensorY.coerceIn(cropRegion.top, cropRegion.bottom)
        
        val focusAreaSize = 150
        val rect = MeteringRectangle(
            max(0, finalSensorX - focusAreaSize), 
            max(0, finalSensorY - focusAreaSize), 
            focusAreaSize * 2, 
            focusAreaSize * 2, 
            MeteringRectangle.METERING_WEIGHT_MAX
        )
        activeMeteringRect = rect
        updateRepeatingRequest(rect)
    }

    @Composable
    fun ProCameraUI() {
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        var isUiHidden by remember { mutableStateOf(false) }
        var isSettingsOpen by remember { mutableStateOf(false) }
        
        val aspect = if (previewSizeState != null) {
            previewSizeState!!.width.toFloat() / previewSizeState!!.height.toFloat()
        } else {
            if (selectedResolution != null) selectedResolution!!.cropWidth.toFloat() / selectedResolution!!.cropHeight.toFloat() else 4f/3f
        }
        
        LaunchedEffect(isRecording) {
            if (isRecording) {
                recordingTimeSeconds = 0
                while (isRecording) {
                    delay(1000)
                    recordingTimeSeconds++
                    recordedFileBytes = rawSpoolerEngine?.getFileSize() ?: 0L
                    droppedFrames = rawSpoolerEngine?.getDroppedFrames() ?: 0
                }
            } else {
                recordedFileBytes = 0L
            }
        }

        LaunchedEffect(isPortrait) {
            if (cameraDevice != null && previewSurface != null) {
                createSession()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Camera Preview Wrapper
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = if (isPortrait) 56.dp else 0.dp,
                        bottom = if (isPortrait) 184.dp else 0.dp,
                        end = if (isPortrait) 0.dp else 150.dp
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { activeDragMenu = "" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(if (isPortrait) 1f / aspect else aspect)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -20) isUiHidden = true
                                if (dragAmount > 20) isUiHidden = false
                            }
                        }
                ) {
                    // Preview SurfaceView
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surfaceHolder = holder
                                        previewSurface = holder.surface
                                        if (cameraDevice != null) createSession()
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                        viewWidth = w; viewHeight = h
                                        if (cameraDevice != null && previewSurface != null) {
                                            createSession()
                                        }
                                    }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { 
                                        previewSurface = null 
                                        surfaceHolder = null
                                        viewWidth = 0
                                        viewHeight = 0
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Viewport Gesture & Drawing overlay
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        activeDragMenu = ""
                                        is3ALocked = false
                                        tapX = offset.x
                                        tapY = offset.y
                                        showTapCircle = true
                                        tapCircleTimerJob?.cancel()
                                        tapCircleTimerJob = lifecycleScope.launch {
                                            delay(1000)
                                            showTapCircle = false
                                        }
                                        handleTouchToFocus(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                                    },
                                    onLongPress = { offset ->
                                        tapX = offset.x
                                        tapY = offset.y
                                        showTapCircle = true
                                        handleTouchToFocus(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                                        is3ALocked = true
                                        updateRepeatingRequest()
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        if (isAutoExposure) {
                                            val evDelta = (dragAmount / 35f)
                                            evAccumulator += evDelta
                                            val evInt = evAccumulator.toInt()
                                            if (evInt != 0) {
                                                evCompensation = (evCompensation + evInt).coerceIn(minEvCompensation, maxEvCompensation)
                                                evAccumulator -= evInt
                                                updateRepeatingRequest()
                                                saveSettings()
                                                showEvFeedback = true
                                                evFeedbackTimerJob?.cancel()
                                                evFeedbackTimerJob = lifecycleScope.launch {
                                                    delay(1500)
                                                    showEvFeedback = false
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        evAccumulator = 0f
                                    }
                                )
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        
                        // Rule of thirds lines
                        if (!isUiHidden) {
                            drawLine(Color.White.copy(alpha = 0.15f), Offset(w / 3, 0f), Offset(w / 3, h), 1.5f)
                            drawLine(Color.White.copy(alpha = 0.15f), Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), 1.5f)
                            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h / 3), Offset(w, h / 3), 1.5f)
                            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), 1.5f)
                        }

                        // Focus/Lock circle feedback
                        if (showTapCircle && tapX >= 0f && tapY >= 0f) {
                            val circleColor = if (is3ALocked) Color.Red else Color.White
                            drawCircle(
                                color = circleColor,
                                radius = 40f,
                                center = Offset(tapX, tapY),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                            )
                            if (is3ALocked) {
                                drawCircle(
                                    color = circleColor,
                                    radius = 10f,
                                    center = Offset(tapX, tapY),
                                    style = androidx.compose.ui.graphics.drawscope.Fill
                                )
                            }
                        }
                    }

                    // HUD monitor overlay
                    if (!isUiHidden && showHudMonitor) {
                        HudMonitor(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
                    }
                }
            }

            // AE/AF Lock Toast Feedback Overlay
            if (is3ALocked && !isUiHidden) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xCC1A1A1A), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "AE / AF / AWB LOCKED",
                        color = Color(0xFFFFD60A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // EV Compensation Value Overlay
            if (showEvFeedback && isAutoExposure && !isUiHidden) {
                val evValue = evCompensation * aeStepSize
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 80.dp)
                        .background(Color(0xCC1A1A1A), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "EV: ${if (evValue > 0) "+" else ""}${String.format("%.2f", evValue)}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Recording stats overlay
            if (isRecording && !isUiHidden) {
                val formattedTime = String.format("%02d:%02d:%02d", recordingTimeSeconds / 3600, (recordingTimeSeconds % 3600) / 60, recordingTimeSeconds % 60)
                val formattedSize = when {
                    recordedFileBytes > 1024 * 1024 * 1024 -> String.format("%.2f GB", recordedFileBytes / (1024f * 1024f * 1024f))
                    recordedFileBytes > 1024 * 1024 -> String.format("%.2f MB", recordedFileBytes / (1024f * 1024f))
                    recordedFileBytes > 1024 -> String.format("%.2f KB", recordedFileBytes / 1024f)
                    else -> "$recordedFileBytes B"
                }
                val dropStr = if (droppedFrames > 0) " • Drops: $droppedFrames" else ""
                
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (isPortrait) 76.dp else 16.dp)
                        .background(Color(0x80CC0000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color.Red, CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$formattedTime • $formattedSize$dropStr",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Glassmorphic Top/Header Bar
            if (!isUiHidden) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Color(0x80000000))
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RAW RECORDER",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                    
                    Box(
                        modifier = Modifier
                            .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                            .clickable { isSettingsOpen = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("SET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Controls section
            if (!isUiHidden) {
                if (isPortrait) {
                    // Portrait bottom controls layout
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0xFF0D0D0F))
                            .padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. Parameter display & selector cards
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val lensStr = when (selectedLens?.name) {
                                "UW" -> "UW"
                                "W" -> "W"
                                "T" -> "T"
                                "Main" -> "W"
                                else -> selectedLens?.name ?: "W"
                            }
                            val shutterStr = if (isAutoExposure) "AUTO" else formatShutterSpeed(shutterSpeedNanos)
                            val isoStr = if (isAutoExposure) "AUTO" else "$isoValue"
                            val focusStr = if (isAutoFocus) {
                                "AUTO"
                            } else if (focusDistance == 0f) {
                                "∞"
                            } else {
                                val m = 1f / focusDistance
                                if (m >= 1f) String.format("%.1fm", m) else String.format("%dcm", (m * 100).toInt())
                            }
                            val tempStr = if (isAutoWb) "AUTO" else "${kelvinValue.toInt()}K"

                            ParameterCard("LENS", lensStr, activeDragMenu == "LENSES", 
                                onClick = { activeDragMenu = if (activeDragMenu == "LENSES") "" else "LENSES" },
                                onDoubleTap = {}
                            )
                            ParameterCard("SHUTTER", shutterStr, activeDragMenu == "EXPOSURE", 
                                onClick = { activeDragMenu = if (activeDragMenu == "EXPOSURE") "" else "EXPOSURE" },
                                onDoubleTap = { isAutoExposure = true; updateRepeatingRequest(); saveSettings() }
                            )
                            ParameterCard("ISO", isoStr, activeDragMenu == "ISO",
                                onClick = { activeDragMenu = if (activeDragMenu == "ISO") "" else "ISO" },
                                onDoubleTap = { isAutoExposure = true; updateRepeatingRequest(); saveSettings() }
                            )
                            ParameterCard("FOCUS", focusStr, activeDragMenu == "FOCUS",
                                onClick = { activeDragMenu = if (activeDragMenu == "FOCUS") "" else "FOCUS" },
                                onDoubleTap = { isAutoFocus = true; activeMeteringRect = null; updateRepeatingRequest(); saveSettings() }
                            )
                            ParameterCard("TEMP", tempStr, activeDragMenu == "TEMP",
                                onClick = { activeDragMenu = if (activeDragMenu == "TEMP") "" else "TEMP" },
                                onDoubleTap = { isAutoWb = true; updateRepeatingRequest(); saveSettings() }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 2. Mode Slider / Text Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Text(
                                text = "PHOTO",
                                color = if (isPhotoMode) Color.White else Color.Gray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { 
                                    isPhotoMode = true
                                    saveSettings()
                                }
                            )
                            Text(
                                text = "VIDEO",
                                color = if (!isPhotoMode) Color.White else Color.Gray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { 
                                    isPhotoMode = false
                                    saveSettings()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3. Shutter bar (Gallery, Shutter, Rotate)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Gallery button
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable {
                                        val intent = android.content.Intent(this@MainActivity, com.example.rawrecorder.gallery.GalleryActivity::class.java)
                                        startActivity(intent)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("GAL", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Shutter
                            ShutterButton()

                            // Native Rotation toggle
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable {
                                        val newRotation = if (appRotationDegrees == 0) 90 else 0
                                        appRotationDegrees = newRotation
                                        saveSettings()
                                        requestedOrientation = if (newRotation == 90) {
                                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ROT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Landscape right controls panel layout
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(130.dp)
                            .background(Color(0xFF0D0D0F))
                            .padding(vertical = 24.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top: Settings & Rotate
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Settings Button
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable { isSettingsOpen = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("SET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Rotate Button
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable {
                                        val newRotation = if (appRotationDegrees == 0) 90 else 0
                                        appRotationDegrees = newRotation
                                        saveSettings()
                                        requestedOrientation = if (newRotation == 90) {
                                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ROT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Middle: Mode Switcher & Shutter
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Mode Switcher (rotated text)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "PHOTO",
                                    color = if (isPhotoMode) Color.White else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.rotate(-90f).clickable { 
                                        isPhotoMode = true
                                        saveSettings()
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "VIDEO",
                                    color = if (!isPhotoMode) Color.White else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.rotate(-90f).clickable { 
                                        isPhotoMode = false
                                        saveSettings()
                                    }
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            // Shutter
                            ShutterButton()
                        }

                        // Bottom: Gallery
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                .clickable {
                                    val intent = android.content.Intent(this@MainActivity, com.example.rawrecorder.gallery.GalleryActivity::class.java)
                                    startActivity(intent)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("GAL", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Landscape parameters row at the bottom of screen overlaying the preview
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 16.dp, start = 16.dp)
                            .width(360.dp)
                            .background(Color(0xF20D0D0F), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val lensStr = when (selectedLens?.name) {
                            "UW" -> "UW"
                            "W" -> "W"
                            "T" -> "T"
                            "Main" -> "W"
                            else -> selectedLens?.name ?: "W"
                        }
                        val shutterStr = if (isAutoExposure) "AUTO" else formatShutterSpeed(shutterSpeedNanos)
                        val isoStr = if (isAutoExposure) "AUTO" else "$isoValue"
                        val focusStr = if (isAutoFocus) {
                            "AUTO"
                        } else if (focusDistance == 0f) {
                            "∞"
                        } else {
                            val m = 1f / focusDistance
                            if (m >= 1f) String.format("%.1fm", m) else String.format("%dcm", (m * 100).toInt())
                        }
                        val tempStr = if (isAutoWb) "AUTO" else "${kelvinValue.toInt()}K"

                        ParameterCard("LENS", lensStr, activeDragMenu == "LENSES", 
                            onClick = { activeDragMenu = if (activeDragMenu == "LENSES") "" else "LENSES" },
                            onDoubleTap = {}
                        )
                        ParameterCard("SHUTTER", shutterStr, activeDragMenu == "EXPOSURE", 
                            onClick = { activeDragMenu = if (activeDragMenu == "EXPOSURE") "" else "EXPOSURE" },
                            onDoubleTap = { isAutoExposure = true; updateRepeatingRequest(); saveSettings() }
                        )
                        ParameterCard("ISO", isoStr, activeDragMenu == "ISO",
                            onClick = { activeDragMenu = if (activeDragMenu == "ISO") "" else "ISO" },
                            onDoubleTap = { isAutoExposure = true; updateRepeatingRequest(); saveSettings() }
                        )
                        ParameterCard("FOCUS", focusStr, activeDragMenu == "FOCUS",
                            onClick = { activeDragMenu = if (activeDragMenu == "FOCUS") "" else "FOCUS" },
                            onDoubleTap = { isAutoFocus = true; activeMeteringRect = null; updateRepeatingRequest(); saveSettings() }
                        )
                        ParameterCard("TEMP", tempStr, activeDragMenu == "TEMP",
                            onClick = { activeDragMenu = if (activeDragMenu == "TEMP") "" else "TEMP" },
                            onDoubleTap = { isAutoWb = true; updateRepeatingRequest(); saveSettings() }
                        )
                    }
                }
            }

            // Parameter Value Adjustment Bar (rendered in front of controls when active)
            if (!isUiHidden && activeDragMenu.isNotEmpty()) {
                ParameterAdjustmentBar(activeDragMenu, isPortrait)
            }

            // Settings Sheet Overlay
            SettingsOverlay(
                isOpen = isSettingsOpen,
                onClose = { isSettingsOpen = false }
            )
        }
    }

    // Helper to adjust parameters from cards or dial wheel drags
    @Composable
    private fun BoxScope.ParameterAdjustmentBar(menu: String, isPortrait: Boolean) {
        Box(
            modifier = Modifier
                .align(if (isPortrait) Alignment.BottomCenter else Alignment.BottomStart)
                .padding(
                    bottom = if (isPortrait) 208.dp else 88.dp,
                    start = if (isPortrait) 12.dp else 16.dp,
                    end = if (isPortrait) 12.dp else 0.dp
                )
                .fillMaxWidth(if (isPortrait) 1f else 0.65f)
                .height(116.dp)
                .background(Color(0xF21A1A1C), RoundedCornerShape(16.dp))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}, // prevent click leak
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (menu) {
                    "LENSES" -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                availableLenses.forEach { lens ->
                                    val isSelected = selectedLens == lens
                                    val label = when (lens.name) {
                                        "UW" -> "UW"
                                        "W" -> "W"
                                        "T" -> "T"
                                        "Main" -> "W"
                                        else -> lens.name
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(if (isSelected) Color(0xFFFFD60A) else Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                                            .border(0.5.dp, if (isSelected) Color.Transparent else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                            .clickable {
                                                switchLens(lens)
                                            }
                                            .padding(horizontal = 18.dp, vertical = 10.dp)
                                    ) {
                                        Text(label, color = if (isSelected) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    "EXPOSURE" -> {
                        val logMinShutter = ln(minShutterNanos.toDouble())
                        val logMaxShutter = ln(maxShutterNanos.toDouble())
                        val currentShutterCoerced = shutterSpeedNanos.coerceIn(minShutterNanos, maxShutterNanos)
                        val shutterProgress = ((ln(currentShutterCoerced.toDouble()) - logMinShutter) / (logMaxShutter - logMinShutter)).toFloat()

                        // 1. Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // AUTO Pill
                            Box(
                                modifier = Modifier
                                    .background(if (isAutoExposure) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
                                    .border(if (isAutoExposure) 0.dp else 1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        isAutoExposure = true
                                        updateRepeatingRequest()
                                        saveSettings()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "AUTO",
                                    color = if (isAutoExposure) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Slider
                            Slider(
                                value = shutterProgress,
                                onValueChange = { progress ->
                                    isAutoExposure = false
                                    val newShutter = exp(logMinShutter + progress * (logMaxShutter - logMinShutter)).toLong()
                                    shutterSpeedNanos = newShutter.coerceIn(minShutterNanos, maxShutterNanos)
                                    updateRepeatingRequest()
                                    saveSettings()
                                },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color(0xFFFFD60A),
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                )
                            )

                            // Current Value Text Badge
                            if (!isAutoExposure) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .width(52.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = formatShutterSpeed(shutterSpeedNanos),
                                        color = Color(0xFFFFD60A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // 2. Presets Row
                        val shutterPresets = listOf(
                            125000L to "1/8000", 250000L to "1/4000", 500000L to "1/2000", 
                            1000000L to "1/1000", 2000000L to "1/500", 4000000L to "1/250", 
                            8000000L to "1/125", 16666666L to "1/60", 33333333L to "1/30", 
                            66666666L to "1/15", 125000000L to "1/8", 250000000L to "1/4", 
                            500000000L to "1/2", 1000000000L to "1s", 2000000000L to "2s"
                        ).filter { it.first in minShutterNanos..maxShutterNanos }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            shutterPresets.forEach { (nanos, label) ->
                                val isSelected = !isAutoExposure && shutterSpeedNanos == nanos
                                Box(
                                    modifier = Modifier
                                        .background(if (isSelected) Color(0xFFFFD60A) else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                        .border(0.5.dp, if (isSelected) Color.Transparent else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable {
                                            isAutoExposure = false
                                            shutterSpeedNanos = nanos
                                            updateRepeatingRequest()
                                            saveSettings()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    "ISO" -> {
                        val logMinIso = ln(minIso.toDouble())
                        val logMaxIso = ln(maxIso.toDouble())
                        val currentIsoCoerced = isoValue.coerceIn(minIso, maxIso)
                        val isoProgress = ((ln(currentIsoCoerced.toDouble()) - logMinIso) / (logMaxIso - logMinIso)).toFloat()

                        // 1. Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // AUTO Pill
                            Box(
                                modifier = Modifier
                                    .background(if (isAutoExposure) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
                                    .border(if (isAutoExposure) 0.dp else 1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        isAutoExposure = true
                                        updateRepeatingRequest()
                                        saveSettings()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "AUTO",
                                    color = if (isAutoExposure) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Slider
                            Slider(
                                value = isoProgress,
                                onValueChange = { progress ->
                                    isAutoExposure = false
                                    val newIso = exp(logMinIso + progress * (logMaxIso - logMinIso)).toInt()
                                    isoValue = newIso.coerceIn(minIso, maxIso)
                                    updateRepeatingRequest()
                                    saveSettings()
                                },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color(0xFFFFD60A),
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                )
                            )

                            // Current Value Text Badge
                            if (!isAutoExposure) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .width(52.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$isoValue",
                                        color = Color(0xFFFFD60A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // 2. Presets Row
                        val isoPresets = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400).filter { it in minIso..maxIso }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            isoPresets.forEach { iso ->
                                val isSelected = !isAutoExposure && isoValue == iso
                                Box(
                                    modifier = Modifier
                                        .background(if (isSelected) Color(0xFFFFD60A) else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                        .border(0.5.dp, if (isSelected) Color.Transparent else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable {
                                            isAutoExposure = false
                                            isoValue = iso
                                            updateRepeatingRequest()
                                            saveSettings()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "$iso",
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    "FOCUS" -> {
                        // 1. Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // AUTO Pill
                            Box(
                                modifier = Modifier
                                    .background(if (isAutoFocus) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
                                    .border(if (isAutoFocus) 0.dp else 1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        isAutoFocus = true
                                        activeMeteringRect = null
                                        updateRepeatingRequest()
                                        saveSettings()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "AUTO",
                                    color = if (isAutoFocus) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Slider
                            Slider(
                                value = focusDistance,
                                onValueChange = {
                                    isAutoFocus = false
                                    activeMeteringRect = null
                                    focusDistance = it
                                    updateRepeatingRequest()
                                    saveSettings()
                                },
                                valueRange = 0.0f..10.0f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color(0xFFFFD60A),
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                )
                            )

                            // Current Value Text Badge
                            if (!isAutoFocus) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .width(52.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val focusDisplayVal = if (focusDistance == 0f) {
                                        "∞"
                                    } else {
                                        val m = 1f / focusDistance
                                        if (m >= 1f) String.format("%.1fm", m) else String.format("%dcm", (m * 100).toInt())
                                    }
                                    Text(
                                        text = focusDisplayVal,
                                        color = Color(0xFFFFD60A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // 2. Presets Row
                        val focusPresets = listOf(
                            0.0f to "Infinity", 
                            1.0f to "1.0m", 
                            2.0f to "0.5m", 
                            5.0f to "0.2m", 
                            10.0f to "Macro"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            focusPresets.forEach { (dist, label) ->
                                val isSelected = !isAutoFocus && focusDistance == dist
                                Box(
                                    modifier = Modifier
                                        .background(if (isSelected) Color(0xFFFFD60A) else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                        .border(0.5.dp, if (isSelected) Color.Transparent else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable {
                                            isAutoFocus = false
                                            activeMeteringRect = null
                                            focusDistance = dist
                                            updateRepeatingRequest()
                                            saveSettings()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    "TEMP" -> {
                        // 1. Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // AUTO Pill
                            Box(
                                modifier = Modifier
                                    .background(if (isAutoWb) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
                                    .border(if (isAutoWb) 0.dp else 1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        isAutoWb = true
                                        updateRepeatingRequest()
                                        saveSettings()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "AUTO",
                                    color = if (isAutoWb) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Slider
                            Slider(
                                value = kelvinValue,
                                onValueChange = {
                                    isAutoWb = false
                                    kelvinValue = it
                                    updateRepeatingRequest()
                                    saveSettings()
                                },
                                valueRange = 2000f..10000f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color(0xFFFFD60A),
                                    inactiveTrackColor = Color(0x33FFFFFF)
                                )
                            )

                            // Current Value Text Badge
                            if (!isAutoWb) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .width(52.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${kelvinValue.toInt()}K",
                                        color = Color(0xFFFFD60A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // 2. Presets Row
                        val tempPresets = listOf(
                            3200f to "Tungsten", 
                            4000f to "Fluorescent", 
                            5500f to "Daylight", 
                            6500f to "Cloudy", 
                            7500f to "Shade"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tempPresets.forEach { (temp, label) ->
                                val isSelected = !isAutoWb && kelvinValue == temp
                                Box(
                                    modifier = Modifier
                                        .background(if (isSelected) Color(0xFFFFD60A) else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                        .border(0.5.dp, if (isSelected) Color.Transparent else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable {
                                            isAutoWb = false
                                            kelvinValue = temp
                                            updateRepeatingRequest()
                                            saveSettings()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Helper to draw the animated Shutter button
    @Composable
    private fun ShutterButton() {
        val shutterSize by androidx.compose.animation.core.animateDpAsState(targetValue = if (isRecording) 32.dp else 52.dp)
        val shutterCorner by androidx.compose.animation.core.animateDpAsState(targetValue = if (isRecording) 8.dp else 26.dp)
        val shutterColor = if (isPhotoMode) Color.White else Color.Red

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color.Transparent, CircleShape)
                .clickable {
                    activeDragMenu = ""
                    if (isPhotoMode) {
                        if (rawSpoolerEngine != null) {
                            val orientation = getDngOrientation()
                            PhotoCaptureEngine.capturePhoto(this@MainActivity, rawSpoolerEngine!!, selectedFps, orientation, useGoogleMetadata, {
                                triggerSinglePhotoCapture()
                            }) { finalFile ->
                                Toast.makeText(this@MainActivity, "Photo saved: ${finalFile.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        if (isRecording) {
                            rawSpoolerEngine?.stopRecording()
                            isRecording = false
                            updateRepeatingRequest()
                        } else {
                            val dir = if (usePrivateStorage) getExternalFilesDir(null) else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                            dir?.mkdirs()
                            val prefix = "${captureName}_"
                            var maxIndex = 0
                            val scanDirs = mutableListOf<File>()
                            getExternalFilesDir(null)?.let { scanDirs.add(it) }
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let { scanDirs.add(it) }
                            for (sDir in scanDirs) {
                                if (!sDir.exists()) continue
                                val rawFiles = sDir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".ayushraw") }
                                rawFiles?.forEach { f ->
                                    val numStr = f.name.removePrefix(prefix).removeSuffix(".ayushraw")
                                    val num = numStr.toIntOrNull()
                                    if (num != null && num > maxIndex) {
                                        maxIndex = num
                                    }
                                }
                                val rawRecorderDir = File(sDir, "RawRecorder")
                                if (rawRecorderDir.exists()) {
                                    val subDirs = rawRecorderDir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
                                    subDirs?.forEach { d ->
                                        val numStr = d.name.removePrefix(prefix)
                                        val num = numStr.toIntOrNull()
                                        if (num != null && num > maxIndex) {
                                            maxIndex = num
                                        }
                                    }
                                }
                            }
                            val nextIndex = maxIndex + 1
                            val fileName = String.format("%s_%04d.ayushraw", captureName, nextIndex)
                            val file = File(dir, fileName)
                            val orientation = getDngOrientation()
                            rawSpoolerEngine?.startRecording(file, selectedFps, orientation, useGoogleMetadata)
                            isRecording = true
                            updateRepeatingRequest()
                        }
                    }
                }
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer Ring
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(4.dp, Color.White, CircleShape)
            )
            
            // Inner Fill (Animated shape)
            Box(
                modifier = Modifier
                    .size(shutterSize)
                    .background(shutterColor, RoundedCornerShape(shutterCorner))
            )
        }
    }

    // Helper for HUD Parameters monitor overlay
    @Composable
    private fun HudMonitor(modifier: Modifier = Modifier) {
        val formatStr = "RAW10"
        val shutterStr = if (isAutoExposure) "Auto EXP" else "1/${1000000000L / max(1, shutterSpeedNanos)}s"
        val isoStr = if (isAutoExposure) "Auto ISO" else "ISO $isoValue"
        val tempStr = if (isAutoWb) "Auto WB" else "${kelvinValue.toInt()}K"
        val focusStr = if (isAutoFocus) "AF-C" else "MF ${String.format("%.2f", focusDistance)}m"
        val stabStr = if (stabilityMode == StabilityMode.OFF) "" else " • ${stabilityMode.name}"

        Box(
            modifier = modifier
                .background(Color(0xE60D0D0F), RoundedCornerShape(16.dp))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "$formatStr • $shutterStr • $isoStr • $tempStr • $focusStr$stabStr",
                color = Color.LightGray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    // Param cards
    @Composable
    private fun ParameterCard(
        label: String, 
        value: String, 
        isActive: Boolean, 
        onClick: () -> Unit,
        onDoubleTap: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier
                .width(64.dp)
                .heightIn(min = 54.dp)
                .background(
                    if (isActive) Color(0x4DFFFFFF) else Color(0x26FFFFFF),
                    RoundedCornerShape(8.dp)
                )
                .border(
                    width = if (isActive) 1.dp else 0.dp,
                    color = if (isActive) Color.White.copy(alpha = 0.5f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .pointerInput(isActive) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onTap = { onClick() }
                    )
                }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = if (isActive) Color.White else Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = if (isActive) Color(0xFFFFD60A) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    // Advanced settings Overlay sheet
    @Composable
    private fun SettingsOverlay(
        isOpen: Boolean,
        onClose: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        if (!isOpen) return
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { onClose() }
        ) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(380.dp)
                    .clickable(enabled = false) {}, // prevent closing when clicking inside
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFC1C1C1E))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Advanced Settings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                .clickable { onClose() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("CLOSE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Capture prefix text field
                    var tempCaptureName by remember(captureName) { mutableStateOf(captureName) }
                    OutlinedTextField(
                        value = tempCaptureName,
                        onValueChange = { 
                            tempCaptureName = it
                            captureName = it
                            saveSettings()
                        },
                        label = { Text("Capture Name Prefix", color = Color.Gray) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Storage choice switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Private Storage", color = Color.White, fontSize = 14.sp)
                            Text("Saves files under app internal storage", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(
                            checked = usePrivateStorage,
                            onCheckedChange = { 
                                usePrivateStorage = it
                                saveSettings()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.Gray
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color(0x22FFFFFF))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Stability selector chips
                    Text("Stabilization Mode", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StabilityMode.values().forEach { mode ->
                            val isSelected = stabilityMode == mode
                            Box(
                                modifier = Modifier
                                    .background(if (isSelected) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                                    .clickable { 
                                        stabilityMode = mode
                                        updateRepeatingRequest()
                                        saveSettings()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(mode.name, color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color(0x22FFFFFF))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Resolution list chips
                    Text("Resolution & Aspect Ratio", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedLens?.resolutions?.forEach { res ->
                            val isSelected = selectedResolution == res
                            Box(
                                modifier = Modifier
                                    .background(if (isSelected) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                                    .clickable { 
                                        selectedResolution = res
                                        updateFpsLimits()
                                        createSession()
                                        saveSettings()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("${res.cropWidth}x${res.cropHeight} (${res.aspectName})", color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color(0x22FFFFFF))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // FPS list chips
                    if (!isPhotoMode) {
                        Text("Target Framerate (FPS)", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableFpsForRes.forEach { fps ->
                                val isSelected = selectedFps == fps
                                Box(
                                    modifier = Modifier
                                        .background(if (isSelected) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                                        .clickable { 
                                            selectedFps = fps
                                            updateRepeatingRequest()
                                            saveSettings()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("$fps FPS", color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }


}
