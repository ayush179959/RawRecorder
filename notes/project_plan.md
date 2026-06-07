```markdown
# Comprehensive Architecture & Specification Document: Custom Android RAW Video Spooler (`.ayushraw`)
### Target Device: Google Pixel 6 Pro (Tensor G1 / UFS 3.1)

This document contains the complete production blueprint, project initialization settings, Android source architecture, and desktop asset pipeline required to build a zero-overhead, uncompressed raw video recording engine. This system replicates the custom frame-spooling paradigm popularized by MotionCam Pro, specifically tuned for the physical sensor capabilities of the Google Pixel 6 Pro.

---

## 1. Android Studio Project Initialization Settings

To achieve the performance required for raw data streams, initialize your project in Android Studio with these exact parameters:

### A. New Project Wizard Settings
* **Project Template:** Empty Activity (Jetpack Compose)
* **Application Name:** `AyushRawRecorder`
* **Package Name:** `com.ayush.rawrecorder`
* **Language:** Kotlin
* **Minimum SDK:** `API 33: Android 13.0 (Tiramisu)` 
* **Build Configuration Language:** Kotlin DSL (`build.gradle.kts`)

### B. Module-Level Build Configuration (`app/build.gradle.kts`)
Ensure your compiler targets utilize modern JVM 17 memory optimizations to maximize memory throughput.

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ayush.rawrecorder"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all"
        )
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
}

```

### C. App Manifest Declarations (`AndroidManifest.xml`)

Bypass standard system restrictions by forcing direct access to raw sensor arrays and hardware subsystems.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)">

    <uses-permission android:name="android.permission.CAMERA" />
    
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />
    <uses-feature android:name="android.hardware.camera.raw" android:required="true" />
    <uses-feature android:name="android.hardware.camera.manual_sensor" android:required="true" />
    <uses-feature android:name="android.hardware.camera.manual_post_processing" android:required="true" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AyushRawRecorder">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AyushRawRecorder">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>

```

---

## 2. Target Hardware & Sensor Specifications

The Pixel 6 Pro contains three physical back-facing cameras. The application bypasses runtime logical camera mapping and interacts directly with the physical hardware layer via the following immutable IDs:

| Lens Profile | Hardcoded ID | Native Hardware Resolution | Operating RAW Output Format (`ImageFormat.RAW_SENSOR`) |
| --- | --- | --- | --- |
| **Main (Wide)** | `"0"` | 50MP Samsung ISOCELL GN1 | **12.5MP Binned** ($4080 \times 3060$ pixels) |
| **Ultrawide** | `"2"` | 12MP Sony IMX386 | **12MP Native** ($4000 \times 3000$ pixels) |
| **Telephoto** | `"3"` | 48MP Sony IMX586 | **12MP Binned** ($4000 \times 3000$ pixels) |

### The Data Rate Blueprint (Main Sensor)

* **Pixel Matrix:** $4080 \times 3060 = 12,484,800$ pixels.
* **Uncompressed 16-bit Container Payload:** Each pixel occupies 2 bytes inside the standard system buffer. Total frame footprint = $24.96\text{ MB}$.
* **Throughput Demands:** * At 24 FPS: $599.04\text{ MB/s}$ continuous binary stream.
* At 30 FPS: $748.80\text{ MB/s}$ continuous binary stream.


* **Storage Destination Target:** Direct sequential byte pipeline via `FileChannel` to standard internal app directories (e.g., `context.getExternalFilesDir(null)`). This completely avoids the indexing and tracking latency overhead introduced by Android's public MediaStore database.

---

## 3. High-Speed Memory & I/O Pipeline Architecture

To prevent Java Virtual Machine Garbage Collection (GC) pauses from causing frame drops during live recordings, the recording loop must remain entirely allocation-free.

```
[Camera Sensor Subsystem]
           │ (ImageFormat.RAW_SENSOR)
           ▼
[ImageReader Buffer Pool (Max Capacity: 12 Images)]
           │
           ▼  OnImageAvailableListener (Dispatched to High-Priority HandlerThread)
┌────────────────────────────────────────────────────────┐
│ Reusable Frame Loop Block                              │
│  1. Pull Frame Data Pointer (No Object Instantiation)  │
│  2. Populate Pre-allocated 32-Byte Frame Header        │
│  3. Direct Write via FileChannel.write()               │
└────────────────────────────────────────────────────────┘
           │
           ▼
[UFS 3.1 Direct Storage Channel] -> (Outputs single continuous .ayushraw file)

```

1. **Threading Optimization:** The camera sub-session callbacks are decoupled from both the UI thread and standard background workers. They run on a specialized `HandlerThread` bound to Linux kernel scheduling parameters via `Process.THREAD_PRIORITY_URGENT_DISPLAY`.
2. **Elastic RAM Cushion:** The `ImageReader` is configured with a high buffer limit (10–12 frames). This acts as a volatile memory cushion when the phone's UFS 3.1 storage controller throttles sequential write performance due to rising system temperatures.

---

## 4. File Format Specification (`.ayushraw`)

The output file format is a single continuous binary stream split into a permanent global metadata header block, followed by sequential frame data segments.

### A. Global Header Layout (256 Bytes - Written Once)

| Offset (Bytes) | Data Type | Field Name | Technical Definition / Function |
| --- | --- | --- | --- |
| **0 – 7** | `Char[8]` | Magic Word | Constantly set to `"AYUSHRAW"` to validate file profile. |
| **8 – 11** | `Int32` | Width | Frame horizontal layout size in pixels. |
| **12 – 15** | `Int32` | Height | Frame vertical layout size in pixels. |
| **16 – 19** | `Int32` | RowStride | Memory stride footprint across rows in bytes (captures padding). |
| **20 – 23** | `Int32` | CameraID | Active lens ID tracking tag (`0`, `2`, or `3`). |
| **24 – 27** | `Int32` | BitDepth | Sensor native depth value (typically `10` or `12`). |
| **28 – 255** | `Byte` | Padding | Zero-filled buffer reservation for expansion metrics. |

### B. Frame Segment Structural Layout (Repeating Sequence)

Every captured frame appends a 32-byte descriptor chunk directly ahead of its respective binary sensor pixel dump.

```
├─ Frame Segment 0
│  ├── Frame Header (32 Bytes)
│  │   ├── Timestamp (Long - 8B)
│  │   ├── Shutter Speed Nanos (Long - 8B)
│  │   ├── ISO Value (Int - 4B)
│  │   ├── Lens Focus Distance (Float - 4B)
│  │   └── Frame Sequence Index (Long - 8B)
│  └── Raw Sensor Payload (Size = RowStride * Height Bytes)
├─ Frame Segment 1
│  ├── Frame Header (32 Bytes)
│  └── Raw Sensor Payload ...

```

---

## 5. Manual Hardware Controls Core Implementation

To guarantee consistent exposures, all automated camera subsystems are explicitly disabled. Manual configurations are injected directly into each frame's execution cycle.

```kotlin
fun applyManualHardwareControls(builder: CaptureRequest.Builder, shutterNanos: Long, iso: Int, focusDiopters: Float) {
    // 1. Disable Auto Exposure and Lock Manual Inputs
    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNanos)
    builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)

    // 2. Disable Auto Focus and Apply Explicit Lens Distance (0.0f = Infinity, 10.0f = Close-Up)
    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)

    // 3. Disable Auto White Balance and Switch to Manual Matrix Transform Mode
    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
    
    // Explicit D65 Reference Color Gains Example for Pixel 6 Pro Sensors
    val rggbGains = RggbChannelVector(1.92f, 1.00f, 1.00f, 2.11f)
    builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggbGains)

    // 4. Force Activate Physical OIS Hardware & Terminate Digital Frame Alterations
    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
}

```

---

## 6. Complete Kotlin Engine Core Source

This performance-critical class coordinates file writes, pre-allocates reusable buffers, and processes frame data blocks within a zero-allocation execution loop.

```kotlin
package com.ayush.rawrecorder

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class RawSpoolerEngine(
    private val width: Int,
    private val height: Int,
    private val rowStride: Int,
    private val cameraId: Int,
    private val nativeBitDepth: Int = 16
) {
    private val tag = "RawSpoolerEngine"
    
    private var fileOutputStream: FileOutputStream? = null
    private var fileChannel: FileChannel? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    @Volatile var isRecording = false
        private set
        
    private var frameIndex = 0L

    // Pre-allocated static buffers to guarantee absolute isolation from the garbage collector
    private val globalHeaderBuffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
    private val frameHeaderBuffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)

    // Shared manual parameters read by loop execution cycles
    @Volatile private var activeShutterNanos = 41666666L // Defaults to 1/24s
    @Volatile private var activeIso = 400
    @Volatile private var activeFocusDiopters = 0.0f

    fun updateLiveCaptureParameters(shutterNanos: Long, iso: Int, focusDiopters: Float) {
        activeShutterNanos = shutterNanos
        activeIso = iso
        activeFocusDiopters = focusDiopters
    }

    fun prepareEnginePipeline() {
        backgroundThread = HandlerThread("RawEngineStream", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun getEngineHandler(): Handler? = backgroundHandler

    fun startRecording(targetFile: File) {
        if (isRecording) return
        
        try {
            fileOutputStream = FileOutputStream(targetFile)
            fileChannel = fileOutputStream?.channel
            frameIndex = 0L
            
            // Generate and flush out Global Header structure
            globalHeaderBuffer.clear()
            globalHeaderBuffer.put("AYUSHRAW".toByteArray(Charsets.US_ASCII)) // 8 Bytes
            globalHeaderBuffer.putInt(width)                                 // 4 Bytes
            globalHeaderBuffer.putInt(height)                                // 4 Bytes
            globalHeaderBuffer.putInt(rowStride)                             // 4 Bytes
            globalHeaderBuffer.putInt(cameraId)                              // 4 Bytes
            globalHeaderBuffer.putInt(nativeBitDepth)                        // 4 Bytes
            
            while (globalHeaderBuffer.hasRemaining()) {
                globalHeaderBuffer.put(0.toByte()) // Explicit padding fill to 256B boundary
            }
            globalHeaderBuffer.flip()
            fileChannel?.write(globalHeaderBuffer)
            
            isRecording = true
            Log.d(tag, "Binary spooling session started. Path: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize target binary file layout", e)
        }
    }

    val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        // Direct extraction from top-level buffer registry queue
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        if (!isRecording) {
            image.close()
            return@OnImageAvailableListener
        }

        try {
            val plane = image.planes[0]
            val pixelBuffer = plane.buffer

            // Assemble frame chunk headers using current volatile hardware configurations
            frameHeaderBuffer.clear()
            frameHeaderBuffer.putLong(image.timestamp)          // Offset 0 (8B)
            frameHeaderBuffer.putLong(activeShutterNanos)       // Offset 8 (8B)
            frameHeaderBuffer.putInt(activeIso)                 // Offset 16 (4B)
            frameHeaderBuffer.putFloat(activeFocusDiopters)     // Offset 20 (4B)
            frameHeaderBuffer.putLong(frameIndex)               // Offset 24 (8B)
            frameHeaderBuffer.flip()

            // Stream continuous data directly into the system storage channel
            fileChannel?.write(frameHeaderBuffer)
            fileChannel?.write(pixelBuffer)

            frameIndex++
        } catch (e: Exception) {
            Log.e(tag, "Write execution breakdown at segment frame tracking reference: $frameIndex", e)
        } finally {
            // CRITICAL: Always release memory handles instantly to prevent total camera frame freezing
            image.close()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            fileChannel?.close()
            fileOutputStream?.close()
            backgroundThread?.quitSafely()
            Log.d(tag, "Recording stream closed successfully. Total recorded frames: $frameIndex")
        } catch (e: Exception) {
            Log.e(tag, "Exception encountered during pipeline closure sequence", e)
        }
    }
}

```

---

## 7. Desktop Extraction Pipeline (Python Unpacker Script)

This production-grade script reads the custom `.ayushraw` container file, extracts the embedded per-frame metadata headers, handles any camera-level row alignment padding, and generates an Adobe/DaVinci standard DNG raw sequence.

```python
import os
import struct
import numpy as np
import tifffile

def unpack_ayushraw_sequence(source_file, target_output_directory):
    """
    Parses custom .ayushraw continuous binary video containers and generates
    sequentially structured CinemaDNG file structures compatible with NLE workflows.
    """
    if not os.path.exists(target_output_directory):
        os.makedirs(target_output_directory)

    print(f"Opening binary video source path: {source_file}")
    
    with open(source_file, "rb") as raw_stream:
        # 1. Parse Global Document Metadata Header Header (First 256 Bytes)
        global_header_block = raw_stream.read(256)
        if len(global_header_block) < 256:
            print("Extraction error: File size is smaller than the required 256-byte header configuration.")
            return

        magic_word = global_header_block[0:8].decode('ascii', errors='ignore')
        if magic_word != "AYUSHRAW":
            raise ValueError(f"Aborting extraction: Format signature mismatch. Expected 'AYUSHRAW', read: '{magic_word}'")

        width, height, row_stride, camera_id, bit_depth = struct.unpack("<IIIII", global_header_block[8:28])
        print(f"--- Global Configuration Block Detected ---")
        print(f"Resolution Vector : {width} x {height}")
        print(f"Hardware Row Stride: {row_stride} Bytes")
        print(f"Physical Lens ID   : {camera_id}")
        print(f"Sensor Core Depth  : {bit_depth}-bit container layout")
        print(f"-------------------------------------------")

        frame_counter = 0
        single_frame_payload_bytes = row_stride * height

        # 2. Sequential Processing Loop
        while True:
            # Parse localized frame header structure block (32 Bytes)
            frame_header_bytes = raw_stream.read(32)
            if len(frame_header_bytes) < 32:
                print("End of binary sequence stream reached.")
                break

            timestamp, shutter_nanos, iso, focus, idx = struct.unpack("<QQIfQ", frame_header_bytes)
            
            # Extract image byte stream payload matching active stride expectations
            pixel_payload_bytes = raw_stream.read(single_frame_payload_bytes)
            if len(pixel_payload_bytes) < single_frame_payload_bytes:
                print(f"Extraction alert: Truncated data file encountered at structural segment: {idx}")
                break

            # Convert frame chunk into a clean 16-bit unsigned array
            raw_array = np.frombuffer(pixel_payload_bytes, dtype=np.uint16)
            
            # Resolve RowStride padding introduced by the Android OS layer
            calculated_row_pixels = row_stride // 2 # 2 Bytes per unit pixel element
            if calculated_row_pixels != width:
                # Reshape matrix using stride metrics and crop out extra memory allocations
                raw_array = raw_array.reshape((height, calculated_row_pixels))
                raw_array = raw_array[:, :width]
            else:
                raw_array = raw_array.reshape((height, width))

            # 3. Generate CinemaDNG Output Container Configurations
            dng_output_path = os.path.join(target_output_directory, f"frame_{frame_counter:05d}.dng")
            
            # Inject standardized Adobe TIFF Tags to interpret Bayer pattern data
            # Standard Pixel 6 Pro Back-Sensor Color Filter Arrangement is RGGB [Tag 33422: 0, 1, 1, 2]
            tifffile.imwrite(
                dng_output_path,
                raw_array,
                photometric='cfa',
                metadata={
                    'CFARepeatPatternDim': [2, 2],
                    'CFAPattern': [0, 1, 1, 2], 
                    'BlackLevel': 64,          # Device sensor black baseline 
                    'WhiteLevel': 1023         # 10-bit color signal ceiling limit
                }
            )

            # Display running telemetry log inside extraction console
            shutter_fraction = f"1/{int(1e9 / shutter_nanos)}" if shutter_nanos > 0 else "0"
            print(f"Unpacked -> Frame: {frame_counter:05d} | Seq ID: {idx} | ISO: {iso} | Shutter: {shutter_fraction}s | Focus: {focus:.2f} Diopters")
            
            frame_counter += 1

    print(f"\n[Extraction Completed] Generated {frame_counter} sequential RAW frames inside: '{target_output_directory}'")

# Execution Entry Hook Example
if __name__ == "__main__":
    # Modify paths as required for your local machine execution environment
    unpack_ayushraw_sequence("capture_test.ayushraw", "./extracted_cinema_dngs")

```

---

## 8. Code Generation Prompt for Secondary AI Systems

*Copy and paste this structured prompt into an advanced coding assistant to automatically generate the remaining UI components, camera state handlers, and interface wiring for this app.*

```text
You are an expert mobile system software engineer specializing in low-overhead execution pipelines, high-rate I/O scheduling, and the Android Camera2 API infrastructure. Your objective is to build out the full application code for a specialized RAW video capture camera app targeting the Google Pixel 6 Pro, adhering precisely to the following structural and logic guidelines:

1. ARCHITECTURAL PATTERN & CORE INCLUSIONS
- Use the com.ayush.rawrecorder package namespace.
- Integrate the provided RawSpoolerEngine class code as the central capture pipeline controller.
- Utilize Jetpack Compose to implement the UI layout. Do not use legacy XML views.

2. CAMERA ACCESS AND STATE MACHINE LOGIC
- Implement an explicit Runtime Permission Request routine to verify and grant access to android.permission.CAMERA before launching the camera engine.
- Hardcode direct connections to physical lens arrays: Main (ID "0", 12.5MP, binned), Ultrawide (ID "2", 12MP), and Telephoto (ID "3", 12MP, binned).
- Initialize your camera capture session utilizing TEMPLATE_MANUAL to bypass all automatic firmware overrides.

3. COUPLING SLIDERS TO THE PIPELINE
- Build clean, interactive Jetpack Compose sliders that bind directly to the following hardware capture request metrics in real time:
  * Exposure Shutter Speed: Map a range from 1/8000s up to 1/12s, converted and stored explicitly as Nanoseconds inside CaptureRequest.SENSOR_EXPOSURE_TIME.
  * ISO Sensitivity: Map a range from ISO 50 up to ISO 3200, applied to CaptureRequest.SENSOR_SENSITIVITY.
  * Lens Focus Distance: Map a float slider from 0.0f (Infinity Focus) up to 10.0f (Macro Focus Point), bound to CaptureRequest.LENS_FOCUS_DISTANCE.
- Connect your manual slider state outputs directly to RawSpoolerEngine.updateLiveCaptureParameters() so variables stay aligned across frame boundaries.

4. RECORDING AND METRICS VISUALIZATION
- Place a clearly visible, red Record Toggle button at the base of the UI layout.
- When activated, determine the file path using context.getExternalFilesDir(null), pass this target File object to RawSpoolerEngine.startRecording(), and lock the lens selection menu during recording.
- Implement a real-time tracking display that accurately reports: Active Storage Path, Elapsed Recording Time, and Cumulative Written File Size.

Generate the complete, fully formed MainActivity.kt file code alongside any necessary companion helper files. Avoid truncated code paths, comments indicating placeholder regions, or omitted error handling blocks. Ensure that every single lifecycle state transition and asynchronous task execution boundary is fully implemented.

```

```

```