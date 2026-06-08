package com.example.rawrecorder

import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class RawSpoolerEngine(
    private val width: Int,
    private val height: Int,
    private val sourceHeight: Int,
    private val cfaPattern: Int,
    private val cm1: IntArray,
    private val cm2: IntArray,
    private val fm1: IntArray,
    private val fm2: IntArray,
    private val ill1: Int,
    private val ill2: Int,
    private val blackLevelPattern: IntArray,
    private val whiteLevel: Int,
    private val cropLeft: Int,
    private val cropTop: Int,
    private val cropWidth: Int,
    private val cropHeight: Int,
    private val sensorType: Int,
    private val cal1: IntArray,
    private val cal2: IntArray,
    private val baselineExpNum: Int,
    private val baselineExpDen: Int,
    private val noiseProfile: FloatArray,
    private val lensIntrinsic: FloatArray,
    private val lensDistortion: FloatArray,
    private val lensAperture: Float,
    private val lensFocalLength: Float,
    private val deviceMake: String,
    private val deviceModel: String,
    private val preWidth: Int,
    private val preHeight: Int
) {
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    private var fileChannel: FileChannel? = null
    private var isRecording = false
    private var isSingleFrame = false
    private var frameCount = 0

    fun isRecording(): Boolean = isRecording
    
    private var lastImageTimestamp = 0L
    private var droppedFramesCount = 0
    private var expectedFrameDurationNs = 33333333L

    
    private var liveAwbR = 1.0f
    private var liveAwbG_even = 1.0f
    private var liveAwbG_odd = 1.0f
    private var liveAwbB = 1.0f
    
    private var liveShutter = 0L
    private var liveIso = 0
    private var liveFocus = 0f
    
    fun prepareEnginePipeline() {
        handlerThread = HandlerThread("RawSpoolerThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
    }
    
    fun getEngineHandler(): Handler? = handler
    
    fun getDroppedFrames(): Int = droppedFramesCount
    
    fun startRecording(file: File, fps: Int, dngOrientation: Int, useGoogleMetadata: Boolean, singleFrame: Boolean = false) {
        isSingleFrame = singleFrame
        frameCount = 0
        lastImageTimestamp = 0L
        droppedFramesCount = 0
        expectedFrameDurationNs = 1_000_000_000L / fps.toLong()
        fileChannel = FileOutputStream(file, false).channel
        
        val globalHeaderBuffer = ByteBuffer.allocateDirect(1024)
        globalHeaderBuffer.order(ByteOrder.LITTLE_ENDIAN)
        globalHeaderBuffer.put("AYUSHRAW".toByteArray())
        globalHeaderBuffer.putInt(width)
        globalHeaderBuffer.putInt(height)
        globalHeaderBuffer.putInt(0) // Will be updated to rowStride dynamically on first frame
        globalHeaderBuffer.putInt(cfaPattern)
        globalHeaderBuffer.putInt(10) // RAW10
        globalHeaderBuffer.putInt(ill1)
        globalHeaderBuffer.putInt(ill2)
        for (i in 0 until 18) globalHeaderBuffer.putInt(cm1[i])
        for (i in 0 until 18) globalHeaderBuffer.putInt(cm2[i])
        for (i in 0 until 18) globalHeaderBuffer.putInt(fm1[i])
        for (i in 0 until 18) globalHeaderBuffer.putInt(fm2[i])
        
        for (i in 0 until 18) globalHeaderBuffer.putInt(cal1[i])
        for (i in 0 until 18) globalHeaderBuffer.putInt(cal2[i])
        
        for (i in 0 until 4) globalHeaderBuffer.putInt(blackLevelPattern[i])
        globalHeaderBuffer.putInt(whiteLevel)
        globalHeaderBuffer.putInt(cropLeft)
        globalHeaderBuffer.putInt(cropTop)
        globalHeaderBuffer.putInt(cropWidth)
        globalHeaderBuffer.putInt(cropHeight)
        globalHeaderBuffer.putInt(sourceHeight)
        globalHeaderBuffer.putInt(dngOrientation)
        globalHeaderBuffer.putInt(sensorType)
        
        // Write baselineExposure
        globalHeaderBuffer.putInt(baselineExpNum)
        globalHeaderBuffer.putInt(baselineExpDen)
        
        // Write noiseProfile (8 floats = 32 bytes)
        for (i in 0 until 8) globalHeaderBuffer.putFloat(noiseProfile[i])
        
        // Write lensIntrinsic (5 floats = 20 bytes)
        for (i in 0 until 5) globalHeaderBuffer.putFloat(lensIntrinsic[i])
        
        // Write lensDistortion (5 floats = 20 bytes)
        for (i in 0 until 5) globalHeaderBuffer.putFloat(lensDistortion[i])
        
        // Write lensAperture and lensFocalLength (4 bytes each)
        globalHeaderBuffer.putFloat(lensAperture)
        globalHeaderBuffer.putFloat(lensFocalLength)
        
        // Write deviceMake (32 bytes ASCII)
        val makeBytes = ByteArray(32)
        val makeSrc = deviceMake.toByteArray(Charsets.US_ASCII)
        System.arraycopy(makeSrc, 0, makeBytes, 0, Math.min(makeSrc.size, 32))
        globalHeaderBuffer.put(makeBytes)
        
        // Write deviceModel (32 bytes ASCII)
        val modelBytes = ByteArray(32)
        val modelSrc = deviceModel.toByteArray(Charsets.US_ASCII)
        System.arraycopy(modelSrc, 0, modelBytes, 0, Math.min(modelSrc.size, 32))
        globalHeaderBuffer.put(modelBytes)
        
        // Write preWidth and preHeight (4 bytes each)
        globalHeaderBuffer.putInt(preWidth)
        globalHeaderBuffer.putInt(preHeight)
        
        // Pad the rest of the 1024-byte header
        while (globalHeaderBuffer.hasRemaining()) {
            globalHeaderBuffer.put(0.toByte())
        }
        
        globalHeaderBuffer.position(0)
        fileChannel?.write(globalHeaderBuffer)
        isRecording = true
    }
    
    fun stopRecording() {
        isRecording = false
        handler?.post {
            fileChannel?.close()
            fileChannel = null
        }
    }
    
    fun updateLiveAwbGains(r: Float, ge: Float, go: Float, b: Float) {
        liveAwbR = r
        liveAwbG_even = ge
        liveAwbG_odd = go
        liveAwbB = b
    }
    
    fun updateLiveCaptureParameters(shutter: Long, iso: Int, focus: Float) {
        liveShutter = shutter
        liveIso = iso
        liveFocus = focus
    }
    
    val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        
        if (!isRecording || fileChannel == null) {
            image.close()
            return@OnImageAvailableListener
        }
        
        val plane = image.planes[0]
        val pixelBuffer = plane.buffer
        val rowStride = plane.rowStride
        val ts = image.timestamp
        
        if (frameCount > 0 && lastImageTimestamp != 0L) {
            val delta = ts - lastImageTimestamp
            val dropped = Math.round(delta.toDouble() / expectedFrameDurationNs.toDouble()).toInt() - 1
            if (dropped > 0) {
                droppedFramesCount += dropped
            }
        }
        lastImageTimestamp = ts
        
        if (frameCount == 0) {
            val strideBuffer = ByteBuffer.allocateDirect(4)
            strideBuffer.order(ByteOrder.LITTLE_ENDIAN)
            strideBuffer.putInt(rowStride)
            strideBuffer.position(0)
            fileChannel?.position(16)
            fileChannel?.write(strideBuffer)
            fileChannel?.position(1024)
        }
        
        val frameHeader = ByteBuffer.allocateDirect(48)
        frameHeader.order(ByteOrder.LITTLE_ENDIAN)
        frameHeader.putLong(ts)
        frameHeader.putLong(liveShutter)
        frameHeader.putInt(liveIso)
        frameHeader.putFloat(liveFocus)
        frameHeader.putInt(frameCount)
        frameHeader.putFloat(liveAwbR)
        frameHeader.putFloat(liveAwbG_even)
        frameHeader.putFloat(liveAwbG_odd)
        frameHeader.putFloat(liveAwbB)
        frameHeader.position(0)
        
        fileChannel?.write(frameHeader)
        
        // Write the FULL buffer for RAW10. No zero-copy cropping!
        val expectedBytes = height * rowStride
        val actualBytes = pixelBuffer.remaining()
        
        fileChannel?.write(pixelBuffer)
        
        // Buffer capacity might be slightly less than height*stride because the LAST row might drop trailing padding.
        if (actualBytes < expectedBytes) {
            val diff = expectedBytes - actualBytes
            val padding = ByteBuffer.allocateDirect(diff)
            fileChannel?.write(padding)
        }
        
        image.close()
        frameCount++
        
        if (isSingleFrame) {
            stopRecording()
        }
    }

    fun getFileSize(): Long {
        return try {
            fileChannel?.size() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
