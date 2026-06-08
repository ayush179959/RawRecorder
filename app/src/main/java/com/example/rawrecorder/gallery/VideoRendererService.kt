package com.example.rawrecorder.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

object VideoRendererService {
    private const val TAG = "VideoRendererService"

    // Compose state variables for UI exposure
    var isRendering by mutableStateOf(false)
    var renderingPath by mutableStateOf("")
    var currentPhase by mutableStateOf("")
    var progressFraction by mutableStateOf(0f)

    private fun readFully(fis: FileInputStream, array: ByteArray): Boolean {
        var bytesRead = 0
        val size = array.size
        while (bytesRead < size) {
            val r = fis.read(array, bytesRead, size - bytesRead)
            if (r < 0) return false
            bytesRead += r
        }
        return true
    }

    fun startDngExtraction(context: Context, ayushrawPath: String) {
        val file = File(ayushrawPath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val rawRecorderDir = File(docDir, "RawRecorder")
        if (!rawRecorderDir.exists()) {
            rawRecorderDir.mkdirs()
        }
        val outputDir = File(rawRecorderDir, file.nameWithoutExtension)

        isRendering = true
        renderingPath = file.absolutePath
        currentPhase = "Extracting DNGs"
        progressFraction = 0f

        Toast.makeText(context, "Starting DNG extraction...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val frameCount = DngExtractor.extractAyushrawToDngs(context, file, outputDir) { progress, total ->
                val totalVal = if (total > 0) total else 1
                progressFraction = progress.toFloat() / totalVal.toFloat()
            }

            if (frameCount <= 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Extraction failed.", Toast.LENGTH_SHORT).show()
                }
                isRendering = false
                return@launch
            }

            currentPhase = "Generating Preview"
            progressFraction = 0.95f

            val previewFile = File(outputDir, "preview.jpg")
            val previewSuccess = generatePreview(file, previewFile)
            Log.d(TAG, "Preview generation success: $previewSuccess")

            val outputFiles = outputDir.listFiles() ?: emptyArray()
            val paths = outputFiles.map { it.absolutePath }.toTypedArray()
            MediaScannerConnection.scanFile(context, paths, null, null)

            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted source raw file: $deleted")
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "DNGs extracted to DCIM/RawRecorder! RAW deleted.", Toast.LENGTH_LONG).show()
            }
            
            isRendering = false
            progressFraction = 1.0f
        }
    }

    fun startHevcExport(context: Context, ayushrawPath: String) {
        val file = File(ayushrawPath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val rawRecorderDir = File(docDir, "RawRecorder")
        if (!rawRecorderDir.exists()) {
            rawRecorderDir.mkdirs()
        }
        val outputFile = File(rawRecorderDir, file.nameWithoutExtension + ".mp4")

        isRendering = true
        renderingPath = file.absolutePath
        currentPhase = "Exporting HEVC"
        progressFraction = 0f

        Toast.makeText(context, "Starting GPU HEVC 10-bit export...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val success = exportAyushrawToHevc(context, file, outputFile) { progress, total ->
                val totalVal = if (total > 0) total else 1
                progressFraction = progress.toFloat() / totalVal.toFloat()
            }

            if (success && outputFile.exists()) {
                MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), null, null)
            }

            // Delete original .ayushraw file after successful export
            if (success && file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted source raw file after HEVC export: $deleted")
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, "HEVC exported successfully! RAW deleted.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "HEVC export failed.", Toast.LENGTH_SHORT).show()
                }
            }

            isRendering = false
            progressFraction = 1.0f
        }
    }

    private suspend fun exportAyushrawToHevc(
        context: Context,
        inputFile: File,
        outputFile: File,
        onProgress: (Int, Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var fis: FileInputStream? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecSurface: CodecInputSurface? = null
        var renderer: GLRenderer? = null
        var success = false
        var muxerStarted = false
        var rawTextureId = 0

        try {
            fis = FileInputStream(inputFile)
            val globalHeader = ByteArray(1024)
            if (!readFully(fis, globalHeader) || String(globalHeader, 0, 8, Charsets.US_ASCII) != "AYUSHRAW") {
                Log.e(TAG, "Invalid global header")
                return@withContext false
            }

            val buffer = ByteBuffer.wrap(globalHeader).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(8)
            val width = buffer.int
            val height = buffer.int
            val rowStride = buffer.int
            val cfaPattern = buffer.int
            val bitDepth = buffer.int

            val ill1 = buffer.int
            val ill2 = buffer.int

            val cm1 = IntArray(18)
            for (i in 0 until 18) cm1[i] = buffer.int
            val cm2 = IntArray(18)
            for (i in 0 until 18) cm2[i] = buffer.int
            val fm1 = IntArray(18)
            for (i in 0 until 18) fm1[i] = buffer.int
            val fm2 = IntArray(18)
            for (i in 0 until 18) fm2[i] = buffer.int

            val cal1 = IntArray(18)
            for (i in 0 until 18) cal1[i] = buffer.int
            val cal2 = IntArray(18)
            for (i in 0 until 18) cal2[i] = buffer.int

            val blPattern = IntArray(4)
            for (i in 0 until 4) blPattern[i] = buffer.int
            val whiteLevel = buffer.int

            val cropLeft = buffer.int
            val cropTop = buffer.int
            val cropWidth = buffer.int
            val cropHeight = buffer.int

            val sourceHeightVal = buffer.int
            val dngOrientationVal = buffer.int
            val sensorType = buffer.int

            val sourceHeight = if (sourceHeightVal in 1..10000) sourceHeightVal else height
            val payloadSize = sourceHeight * rowStride

            val totalFrames = if (payloadSize > 0) ((inputFile.length() - 1024) / (48 + payloadSize)).toInt() else 1

            // MediaCodec requires even dimensions
            val encWidth = width and -2
            val encHeight = height and -2
            val targetFps = 30
            val bitrate = (encWidth * encHeight * targetFps * 0.5f).toInt().coerceIn(10_000_000, 120_000_000)

            Log.d(TAG, "Export HEVC GPU Config: ${encWidth}x${encHeight} @ $targetFps FPS, Bitrate: ${bitrate / 1_000_000} Mbps")

            val mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC
            codec = MediaCodec.createEncoderByType(mimeType)
            val format = MediaFormat.createVideoFormat(mimeType, encWidth, encHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            
            // Setup EGL surface and context wrapping MediaCodec surface
            codecSurface = CodecInputSurface(inputSurface)
            codecSurface.makeCurrent()
            
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Set rotation metadata so portrait recordings display correctly
            val rotationDegrees = when (dngOrientationVal) {
                3 -> 180
                6 -> 90
                8 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                muxer.setOrientationHint(rotationDegrees)
                Log.d(TAG, "Set HEVC orientation hint: $rotationDegrees degrees")
            }

            // Setup GL renderer and shaders
            renderer = GLRenderer()
            renderer.init()

            val cfaTuple = when (cfaPattern) {
                0 -> intArrayOf(0, 1, 1, 2) // RGGB
                1 -> intArrayOf(1, 0, 2, 1) // GRBG
                2 -> intArrayOf(1, 2, 0, 1) // GBRG
                3 -> intArrayOf(2, 1, 1, 0) // BGGR
                else -> intArrayOf(1, 2, 0, 1)
            }

            var isFmValid = false
            for (x in fm1) {
                if (x != 0) {
                    isFmValid = true
                    break
                }
            }

            val FM = if (isFmValid) {
                val m = FloatArray(9)
                for (i in 0 until 9) {
                    val num = fm1[i * 2].toFloat()
                    val den = fm1[i * 2 + 1].toFloat()
                    m[i] = if (den != 0f) num / den else 0f
                }
                m
            } else {
                floatArrayOf(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f
                )
            }

            var allOnes = true
            var allZeros = true
            for (i in 0 until 9) {
                if (FM[i] != 1f) allOnes = false
                if (FM[i] != 0f) allZeros = false
            }
            if (allOnes || allZeros) {
                for (i in 0 until 9) {
                    FM[i] = if (i % 4 == 0) 1f else 0f
                }
            }

            val mXYZToTarget = floatArrayOf(
                 3.1338561f, -1.6168667f, -0.4906146f,
                -0.9787684f,  1.9161415f,  0.0334540f,
                 0.0719453f, -0.2289914f,  1.4052427f
            )

            val combinedMatrix = FloatArray(9)
            combinedMatrix[0] = mXYZToTarget[0]*FM[0] + mXYZToTarget[1]*FM[3] + mXYZToTarget[2]*FM[6]
            combinedMatrix[1] = mXYZToTarget[0]*FM[1] + mXYZToTarget[1]*FM[4] + mXYZToTarget[2]*FM[7]
            combinedMatrix[2] = mXYZToTarget[0]*FM[2] + mXYZToTarget[1]*FM[5] + mXYZToTarget[2]*FM[8]

            combinedMatrix[3] = mXYZToTarget[3]*FM[0] + mXYZToTarget[4]*FM[3] + mXYZToTarget[5]*FM[6]
            combinedMatrix[4] = mXYZToTarget[3]*FM[1] + mXYZToTarget[4]*FM[4] + mXYZToTarget[5]*FM[7]
            combinedMatrix[5] = mXYZToTarget[3]*FM[2] + mXYZToTarget[4]*FM[5] + mXYZToTarget[5]*FM[8]

            combinedMatrix[6] = mXYZToTarget[6]*FM[0] + mXYZToTarget[7]*FM[3] + mXYZToTarget[8]*FM[6]
            combinedMatrix[7] = mXYZToTarget[6]*FM[1] + mXYZToTarget[7]*FM[4] + mXYZToTarget[8]*FM[7]
            combinedMatrix[8] = mXYZToTarget[6]*FM[2] + mXYZToTarget[7]*FM[5] + mXYZToTarget[8]*FM[8]

            // Setup GL textures and coordinate buffers
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            rawTextureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

            GLES20.glViewport(0, 0, encWidth, encHeight)

            val quadPositions = floatArrayOf(
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
            )
            val quadTexCoords = floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            )
            val posBuffer = createFloatBuffer(quadPositions)
            val texBuffer = createFloatBuffer(quadTexCoords)

            val blackLevelFloats = FloatArray(4) { blPattern[it].toFloat() }
            val frameHeader = ByteArray(48)
            val payload = ByteArray(payloadSize)
            
            // Preallocate direct buffer to upload payload texture
            val payloadBuffer = ByteBuffer.allocateDirect(payloadSize)
            payloadBuffer.order(ByteOrder.nativeOrder())

            var frameCount = 0
            val bufferInfo = MediaCodec.BufferInfo()
            val trackIndexRef = intArrayOf(-1)
            val muxerStartedRef = booleanArrayOf(false)
            var inputEndOfStream = false

            while (!inputEndOfStream) {
                var timestampNs = 0L
                var gotFrame = false

                if (readFully(fis, frameHeader)) {
                    val frameBuf = ByteBuffer.wrap(frameHeader).order(ByteOrder.LITTLE_ENDIAN)
                    timestampNs = frameBuf.long
                    val shutter = frameBuf.long
                    val iso = frameBuf.int
                    val focus = frameBuf.float
                    val idx = frameBuf.int
                    val gRed = frameBuf.float
                    val gGreenEven = frameBuf.float
                    val gGreenOdd = frameBuf.float
                    val gBlue = frameBuf.float

                    val rGain = max(0.0001f, gRed)
                    val gGain = max(0.0001f, (gGreenEven + gGreenOdd) / 2.0f)
                    val bGain = max(0.0001f, gBlue)
                    val rVal = gGain / rGain
                    val bVal = gGain / bGain

                    if (readFully(fis, payload)) {
                        gotFrame = true

                        val cropStartRow = ((sourceHeight - height) / 2) and -2
                        
                        // Upload payload to texture
                        payloadBuffer.clear()
                        payloadBuffer.put(payload)
                        payloadBuffer.position(0)
                        
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTextureId)
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, rowStride, sourceHeight,
                            0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, payloadBuffer
                        )

                        // Render frame
                        renderer.drawFrame(
                            rawTextureId, rowStride.toFloat(), sourceHeight.toFloat(),
                            encWidth, encHeight, cropStartRow,
                            cfaTuple, blackLevelFloats, whiteLevel.toFloat(),
                            rVal, bVal, combinedMatrix,
                            posBuffer, texBuffer
                        )

                        // Set presentation timestamp and swap buffer to encoder
                        codecSurface.setPresentationTime(timestampNs)
                        codecSurface.swapBuffers()

                        frameCount++
                        onProgress(frameCount, totalFrames)
                    }
                } else {
                    inputEndOfStream = true
                }

                // Drain output buffers
                drainCodec(codec, muxer, bufferInfo, trackIndexRef, muxerStartedRef)

                if (inputEndOfStream) {
                    codec.signalEndOfInputStream()
                    // Keep draining until we get END_OF_STREAM flag
                    var eosReached = false
                    while (!eosReached) {
                        val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = codec.outputFormat
                            trackIndexRef[0] = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStartedRef[0] = true
                        } else if (outputBufferId >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStartedRef[0]) {
                                val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndexRef[0], outputBuffer, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outputBufferId, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                eosReached = true
                            }
                        } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // Break out if no output is ready yet, but keep loop until EOS is retrieved
                            Thread.sleep(10)
                        }
                    }
                }
            }

            success = true
            muxerStarted = muxerStartedRef[0]
        } catch (e: Exception) {
            Log.e(TAG, "Error during HEVC export: ${e.message}", e)
        } finally {
            try { fis?.close() } catch (e: Exception) {}
            try {
                if (rawTextureId != 0) {
                    val textures = intArrayOf(rawTextureId)
                    GLES20.glDeleteTextures(1, textures, 0)
                }
            } catch (e: Exception) {}
            try { renderer?.release() } catch (e: Exception) {}
            try { codecSurface?.release() } catch (e: Exception) {}
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Exception) {}
        }

        success
    }

    private fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(coords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(coords)
        fb.position(0)
        return fb
    }

    private fun drainCodec(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        trackIndexRef: IntArray,
        muxerStartedRef: BooleanArray
    ) {
        while (true) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 1000L)
            if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                trackIndexRef[0] = muxer.addTrack(newFormat)
                muxer.start()
                muxerStartedRef[0] = true
            } else if (outputBufferId >= 0) {
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0 && muxerStartedRef[0]) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndexRef[0], outputBuffer, bufferInfo)
                }
                codec.releaseOutputBuffer(outputBufferId, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else {
                break
            }
        }
    }

    private fun generatePreview(inputFile: File, outputFile: File): Boolean {
        try {
            FileInputStream(inputFile).use { fis ->
                val globalHeader = ByteArray(1024)
                var bytesRead = 0
                while (bytesRead < 1024) {
                    val r = fis.read(globalHeader, bytesRead, 1024 - bytesRead)
                    if (r < 0) return false
                    bytesRead += r
                }
                
                if (String(globalHeader, 0, 8, Charsets.US_ASCII) != "AYUSHRAW") {
                    return false
                }
                
                val buffer = ByteBuffer.wrap(globalHeader).order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(8)
                val width = buffer.int
                val height = buffer.int
                val rowStride = buffer.int
                val cfaPattern = buffer.int
                val bitDepth = buffer.int
                
                val ill1 = buffer.int
                val ill2 = buffer.int
                
                val cm1 = IntArray(18)
                for (i in 0 until 18) cm1[i] = buffer.int
                val cm2 = IntArray(18)
                for (i in 0 until 18) cm2[i] = buffer.int
                val fm1 = IntArray(18)
                for (i in 0 until 18) fm1[i] = buffer.int
                val fm2 = IntArray(18)
                for (i in 0 until 18) fm2[i] = buffer.int
                
                val cal1 = IntArray(18)
                for (i in 0 until 18) cal1[i] = buffer.int
                val cal2 = IntArray(18)
                for (i in 0 until 18) cal2[i] = buffer.int
                
                val blPattern = IntArray(4)
                for (i in 0 until 4) blPattern[i] = buffer.int
                val whiteLevel = buffer.int
                
                val cropLeft = buffer.int
                val cropTop = buffer.int
                val cropWidth = buffer.int
                val cropHeight = buffer.int
                
                val sourceHeightVal = buffer.int
                val dngOrientationVal = buffer.int
                val sensorType = buffer.int
                
                val sourceHeight = if (sourceHeightVal in 1..10000) sourceHeightVal else height
                val payloadSize = sourceHeight * rowStride
                
                val frameHeader = ByteArray(48)
                var fhBytesRead = 0
                while (fhBytesRead < 48) {
                    val r = fis.read(frameHeader, fhBytesRead, 48 - fhBytesRead)
                    if (r < 0) return false
                    fhBytesRead += r
                }
                
                val frameBuf = ByteBuffer.wrap(frameHeader).order(ByteOrder.LITTLE_ENDIAN)
                val timestamp = frameBuf.long
                val shutter = frameBuf.long
                val iso = frameBuf.int
                val focus = frameBuf.float
                val idx = frameBuf.int
                val gRed = frameBuf.float
                val gGreenEven = frameBuf.float
                val gGreenOdd = frameBuf.float
                val gBlue = frameBuf.float
                
                val rGain = if (gRed > 0.0001f) gRed else 1.0f
                val gGain = if ((gGreenEven + gGreenOdd) > 0.0001f) (gGreenEven + gGreenOdd) / 2.0f else 1.0f
                val bGain = if (gBlue > 0.0001f) gBlue else 1.0f
                val rVal = gGain / rGain
                val bVal = gGain / bGain
                
                val payload = ByteArray(payloadSize)
                var plBytesRead = 0
                while (plBytesRead < payloadSize) {
                    val r = fis.read(payload, plBytesRead, payloadSize - plBytesRead)
                    if (r < 0) return false
                    plBytesRead += r
                }
                
                val rawGrid = ShortArray(width * height)
                val cropStartRow = ((sourceHeight - height) / 2) and -2
                
                var outIdx = 0
                for (y in 0 until height) {
                    var inIdx = (y + cropStartRow) * rowStride
                    for (x in 0 until width step 4) {
                        val b0 = payload[inIdx].toInt() and 0xFF
                        val b1 = payload[inIdx + 1].toInt() and 0xFF
                        val b2 = payload[inIdx + 2].toInt() and 0xFF
                        val b3 = payload[inIdx + 3].toInt() and 0xFF
                        val b4 = payload[inIdx + 4].toInt() and 0xFF
                        
                        val p0 = (b0 shl 2) or (b4 and 0x03)
                        val p1 = (b1 shl 2) or ((b4 shr 2) and 0x03)
                        val p2 = (b2 shl 2) or ((b4 shr 4) and 0x03)
                        val p3 = (b3 shl 2) or ((b4 shr 6) and 0x03)
                        
                        rawGrid[outIdx++] = p0.toShort()
                        rawGrid[outIdx++] = p1.toShort()
                        rawGrid[outIdx++] = p2.toShort()
                        rawGrid[outIdx++] = p3.toShort()
                        
                        inIdx += 5
                    }
                }
                
                val cfaTuple = when (cfaPattern) {
                    0 -> intArrayOf(0, 1, 1, 2) // RGGB
                    1 -> intArrayOf(1, 0, 2, 1) // GRBG
                    2 -> intArrayOf(1, 2, 0, 1) // GBRG
                    3 -> intArrayOf(2, 1, 1, 0) // BGGR
                    else -> intArrayOf(1, 2, 0, 1)
                }
                
                val normGrid = FloatArray(width * height)
                for (y in 0 until height) {
                    val yMod2 = y % 2
                    val yWidth = y * width
                    for (x in 0 until width) {
                        val idxVal = yWidth + x
                        val rawVal = rawGrid[idxVal].toInt() and 0xFFFF
                        val bl = blPattern[yMod2 * 2 + (x % 2)]
                        val den = whiteLevel - bl
                        var norm = if (den > 0) (rawVal - bl).toFloat() / den else 0f
                        val ch = cfaTuple[yMod2 * 2 + (x % 2)]
                        if (ch == 0) norm *= (1.0f / rVal)
                        else if (ch == 2) norm *= (1.0f / bVal)
                        normGrid[idxVal] = norm.coerceIn(0f, 1f)
                    }
                }
                
                var isFmValid = false
                for (x in fm1) {
                    if (x != 0) {
                        isFmValid = true
                        break
                    }
                }
                
                val FM = if (isFmValid) {
                    val m = FloatArray(9)
                    for (i in 0 until 9) {
                        val num = fm1[i * 2].toFloat()
                        val den = fm1[i * 2 + 1].toFloat()
                        m[i] = if (den != 0f) num / den else 0f
                    }
                    m
                } else {
                    floatArrayOf(
                        1f, 0f, 0f,
                        0f, 1f, 0f,
                        0f, 0f, 1f
                    )
                }
                
                var allOnes = true
                var allZeros = true
                for (i in 0 until 9) {
                    if (FM[i] != 1f) allOnes = false
                    if (FM[i] != 0f) allZeros = false
                }
                if (allOnes || allZeros) {
                    for (i in 0 until 9) {
                        FM[i] = if (i % 4 == 0) 1f else 0f
                    }
                }
                
                val mXYZToTarget = floatArrayOf(
                     3.1338561f, -1.6168667f, -0.4906146f,
                    -0.9787684f,  1.9161415f,  0.0334540f,
                     0.0719453f, -0.2289914f,  1.4052427f
                )
                
                val combinedMatrix = FloatArray(9)
                combinedMatrix[0] = mXYZToTarget[0]*FM[0] + mXYZToTarget[1]*FM[3] + mXYZToTarget[2]*FM[6]
                combinedMatrix[1] = mXYZToTarget[0]*FM[1] + mXYZToTarget[1]*FM[4] + mXYZToTarget[2]*FM[7]
                combinedMatrix[2] = mXYZToTarget[0]*FM[2] + mXYZToTarget[1]*FM[5] + mXYZToTarget[2]*FM[8]
                
                combinedMatrix[3] = mXYZToTarget[3]*FM[0] + mXYZToTarget[4]*FM[3] + mXYZToTarget[5]*FM[6]
                combinedMatrix[4] = mXYZToTarget[3]*FM[1] + mXYZToTarget[4]*FM[4] + mXYZToTarget[5]*FM[7]
                combinedMatrix[5] = mXYZToTarget[3]*FM[2] + mXYZToTarget[4]*FM[5] + mXYZToTarget[5]*FM[8]
                
                combinedMatrix[6] = mXYZToTarget[6]*FM[0] + mXYZToTarget[7]*FM[3] + mXYZToTarget[8]*FM[6]
                combinedMatrix[7] = mXYZToTarget[6]*FM[1] + mXYZToTarget[7]*FM[4] + mXYZToTarget[8]*FM[7]
                combinedMatrix[8] = mXYZToTarget[6]*FM[2] + mXYZToTarget[7]*FM[5] + mXYZToTarget[8]*FM[8]

                val scale = 4
                val previewWidth = width / scale
                val previewHeight = height / scale
                val colors = IntArray(previewWidth * previewHeight)
                
                for (py in 0 until previewHeight) {
                    val y = py * scale
                    val yMod2 = y % 2
                    val rowHasRed = (cfaTuple[yMod2 * 2] == 0 || cfaTuple[yMod2 * 2 + 1] == 0)
                    
                    val prevY = if (y > 0) y - 1 else 0
                    val nextY = if (y < height - 1) y + 1 else height - 1
                    
                    val prevYWidth = prevY * width
                    val nextYWidth = nextY * width
                    val currYWidth = y * width
                    
                    for (px in 0 until previewWidth) {
                        val x = px * scale
                        val idxVal = currYWidth + x
                        val ch = cfaTuple[yMod2 * 2 + (x % 2)]
                        
                        val prevX = if (x > 0) x - 1 else 0
                        val nextX = if (x < width - 1) x + 1 else width - 1
                        
                        var r = 0f
                        var g = 0f
                        var b = 0f
                        
                        if (ch == 0) {
                            r = normGrid[idxVal]
                            g = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX] + normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.25f
                            b = (normGrid[prevYWidth + prevX] + normGrid[prevYWidth + nextX] + normGrid[nextYWidth + prevX] + normGrid[nextYWidth + nextX]) * 0.25f
                        } else if (ch == 2) {
                            b = normGrid[idxVal]
                            g = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX] + normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.25f
                            r = (normGrid[prevYWidth + prevX] + normGrid[prevYWidth + nextX] + normGrid[nextYWidth + prevX] + normGrid[nextYWidth + nextX]) * 0.25f
                        } else {
                            g = normGrid[idxVal]
                            if (rowHasRed) {
                                r = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX]) * 0.5f
                                b = (normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.5f
                            } else {
                                r = (normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.5f
                                b = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX]) * 0.5f
                            }
                        }
                        
                        var rout = combinedMatrix[0]*r + combinedMatrix[1]*g + combinedMatrix[2]*b
                        var gout = combinedMatrix[3]*r + combinedMatrix[4]*g + combinedMatrix[5]*b
                        var bout = combinedMatrix[6]*r + combinedMatrix[7]*g + combinedMatrix[8]*b
                        
                        rout = Math.pow(rout.coerceIn(0f, 1f).toDouble(), 0.45).toFloat()
                        gout = Math.pow(gout.coerceIn(0f, 1f).toDouble(), 0.45).toFloat()
                        bout = Math.pow(bout.coerceIn(0f, 1f).toDouble(), 0.45).toFloat()
                        
                        val ir = (rout * 255f).toInt().coerceIn(0, 255)
                        val ig = (gout * 255f).toInt().coerceIn(0, 255)
                        val ib = (bout * 255f).toInt().coerceIn(0, 255)
                        
                        colors[py * previewWidth + px] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
                    }
                }
                
                val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(colors, 0, previewWidth, 0, 0, previewWidth, previewHeight)
                
                FileOutputStream(outputFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }
                bitmap.recycle()
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate preview: ${e.message}", e)
            return false
        }
    }
}

class CodecInputSurface(private val surface: Surface) {
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        var config = chooseEglConfig(10)
        if (config == null) {
            Log.w("Egl", "10-bit config not available, falling back to 8-bit")
            config = chooseEglConfig(8)
        }
        if (config == null) {
            throw RuntimeException("Unable to find a suitable EGLConfig")
        }

        val attrib2_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, attrib2_list, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGL window surface")
        }
    }

    private fun chooseEglConfig(bits: Int): EGLConfig? {
        val redSize = if (bits == 10) 10 else 8
        val greenSize = if (bits == 10) 10 else 8
        val blueSize = if (bits == 10) 10 else 8
        val alphaSize = if (bits == 10) 2 else 8

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, redSize,
            EGL14.EGL_GREEN_SIZE, greenSize,
            EGL14.EGL_BLUE_SIZE, blueSize,
            EGL14.EGL_ALPHA_SIZE, alphaSize,
            EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            if (numConfigs[0] > 0) {
                return configs[0]
            }
        }
        return null
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}

class GLRenderer {
    private var program = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTextureLoc = -1
    private var uTexWidthLoc = -1
    private var uTexHeightLoc = -1
    private var uWidthLoc = -1
    private var uHeightLoc = -1
    private var uCropStartRowLoc = -1
    private var uCfaTupleLoc = -1
    private var uBlackLevelLoc = -1
    private var uWhiteLevelLoc = -1
    private var uAWBGainsLoc = -1
    private var uColorMatrixLoc = -1

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision highp float;
        precision highp int;
        in vec2 vTexCoord;
        out vec4 fragColor;
        uniform sampler2D uTexture;
        uniform float uTexWidth;
        uniform float uTexHeight;
        uniform int uWidth;
        uniform int uHeight;
        uniform int uCropStartRow;
        uniform int uCfaTuple[4];
        uniform float uBlackLevel[4];
        uniform float uWhiteLevel;
        uniform vec2 uAWBGains;
        uniform float uColorMatrix[9];

        float getByte(int bx, int by) {
            vec2 uv = (vec2(float(bx), float(by)) + 0.5) / vec2(uTexWidth, uTexHeight);
            return texture(uTexture, uv).r * 255.0;
        }

        float getRawPixel(int px, int py) {
            int row = py + uCropStartRow;
            int blockX = (px / 4) * 5;
            int pixelIdx = px % 4;

            float b_pixel = getByte(blockX + pixelIdx, row);
            float b_lsb = getByte(blockX + 4, row);

            int lsb = 0;
            int b_lsb_int = int(b_lsb);
            if (pixelIdx == 0) {
                lsb = b_lsb_int & 3;
            } else if (pixelIdx == 1) {
                lsb = (b_lsb_int >> 2) & 3;
            } else if (pixelIdx == 2) {
                lsb = (b_lsb_int >> 4) & 3;
            } else if (pixelIdx == 3) {
                lsb = (b_lsb_int >> 6) & 3;
            }

            return b_pixel * 4.0 + float(lsb);
        }

        float getNorm(int px, int py, int ch) {
            int cx = clamp(px, 0, uWidth - 1);
            int cy = clamp(py, 0, uHeight - 1);

            float rawVal = getRawPixel(cx, cy);

            int xMod2 = cx % 2;
            int yMod2 = cy % 2;
            float bl = uBlackLevel[yMod2 * 2 + xMod2];

            float den = uWhiteLevel - bl;
            float norm = 0.0;
            if (den > 0.0) {
                norm = (rawVal - bl) / den;
            }
            norm = clamp(norm, 0.0, 1.0);

            if (ch == 0) norm *= (1.0 / uAWBGains.x);
            else if (ch == 2) norm *= (1.0 / uAWBGains.y);

            return clamp(norm, 0.0, 1.0);
        }

        void main() {
            int x = int(vTexCoord.x * float(uWidth));
            int y = int((1.0 - vTexCoord.y) * float(uHeight));

            int yMod2 = y % 2;
            int xMod2 = x % 2;
            int ch = uCfaTuple[yMod2 * 2 + xMod2];
            bool rowHasRed = (uCfaTuple[yMod2 * 2] == 0 || uCfaTuple[yMod2 * 2 + 1] == 0);

            int prevY = y - 1;
            int nextY = y + 1;
            int prevX = x - 1;
            int nextX = x + 1;

            float r = 0.0;
            float g = 0.0;
            float b = 0.0;

            if (ch == 0) {
                r = getNorm(x, y, 0);
                g = (getNorm(prevX, y, 1) + getNorm(nextX, y, 1) + getNorm(x, prevY, 1) + getNorm(x, nextY, 1)) * 0.25;
                b = (getNorm(prevX, prevY, 2) + getNorm(nextX, prevY, 2) + getNorm(prevX, nextY, 2) + getNorm(nextX, nextY, 2)) * 0.25;
            } else if (ch == 2) {
                b = getNorm(x, y, 2);
                g = (getNorm(prevX, y, 1) + getNorm(nextX, y, 1) + getNorm(x, prevY, 1) + getNorm(x, nextY, 1)) * 0.25;
                r = (getNorm(prevX, prevY, 0) + getNorm(nextX, prevY, 0) + getNorm(prevX, nextY, 0) + getNorm(nextX, nextY, 0)) * 0.25;
            } else {
                g = getNorm(x, y, 1);
                if (rowHasRed) {
                    r = (getNorm(prevX, y, 0) + getNorm(nextX, y, 0)) * 0.5;
                    b = (getNorm(x, prevY, 2) + getNorm(x, nextY, 2)) * 0.5;
                } else {
                    r = (getNorm(x, prevY, 0) + getNorm(x, nextY, 0)) * 0.5;
                    b = (getNorm(prevX, y, 2) + getNorm(nextX, y, 2)) * 0.5;
                }
            }

            float rout = uColorMatrix[0]*r + uColorMatrix[1]*g + uColorMatrix[2]*b;
            float gout = uColorMatrix[3]*r + uColorMatrix[4]*g + uColorMatrix[5]*b;
            float bout = uColorMatrix[6]*r + uColorMatrix[7]*g + uColorMatrix[8]*b;

            float rLog = clamp(685.0 + 300.0 * log(max(rout, 0.0) * 0.9890396 + 0.0109604) / 2.30258509, 0.0, 1023.0);
            float gLog = clamp(685.0 + 300.0 * log(max(gout, 0.0) * 0.9890396 + 0.0109604) / 2.30258509, 0.0, 1023.0);
            float bLog = clamp(685.0 + 300.0 * log(max(bout, 0.0) * 0.9890396 + 0.0109604) / 2.30258509, 0.0, 1023.0);

            fragColor = vec4(rLog / 1023.0, gLog / 1023.0, bLog / 1023.0, 1.0);
        }
    """.trimIndent()

    fun init() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: $log")
        }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uTexWidthLoc = GLES20.glGetUniformLocation(program, "uTexWidth")
        uTexHeightLoc = GLES20.glGetUniformLocation(program, "uTexHeight")
        uWidthLoc = GLES20.glGetUniformLocation(program, "uWidth")
        uHeightLoc = GLES20.glGetUniformLocation(program, "uHeight")
        uCropStartRowLoc = GLES20.glGetUniformLocation(program, "uCropStartRow")
        uCfaTupleLoc = GLES20.glGetUniformLocation(program, "uCfaTuple")
        uBlackLevelLoc = GLES20.glGetUniformLocation(program, "uBlackLevel")
        uWhiteLevelLoc = GLES20.glGetUniformLocation(program, "uWhiteLevel")
        uAWBGainsLoc = GLES20.glGetUniformLocation(program, "uAWBGains")
        uColorMatrixLoc = GLES20.glGetUniformLocation(program, "uColorMatrix")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader type $type: $log")
        }
        return shader
    }

    fun drawFrame(
        textureId: Int, texWidth: Float, texHeight: Float,
        width: Int, height: Int, cropStartRow: Int,
        cfaTuple: IntArray, blackLevel: FloatArray, whiteLevel: Float,
        rVal: Float, bVal: Float, colorMatrix: FloatArray,
        posBuffer: FloatBuffer, texBuffer: FloatBuffer
    ) {
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glUniform1f(uTexWidthLoc, texWidth)
        GLES20.glUniform1f(uTexHeightLoc, texHeight)
        GLES20.glUniform1i(uWidthLoc, width)
        GLES20.glUniform1i(uHeightLoc, height)
        GLES20.glUniform1i(uCropStartRowLoc, cropStartRow)
        GLES20.glUniform1iv(uCfaTupleLoc, 4, cfaTuple, 0)
        GLES20.glUniform1fv(uBlackLevelLoc, 4, blackLevel, 0)
        GLES20.glUniform1f(uWhiteLevelLoc, whiteLevel)
        GLES20.glUniform2f(uAWBGainsLoc, rVal, bVal)
        GLES20.glUniform1fv(uColorMatrixLoc, 9, colorMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, posBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}
