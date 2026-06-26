package com.example.rawrecorder

import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import android.hardware.camera2.params.LensShadingMap

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
    private val TAG = "RawSpoolerEngine"
    
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    private var fileChannel: FileChannel? = null
    @Volatile private var isRecording = false
    private var isSingleFrame = false
    private var frameCount = 0
    
    // Producer-Consumer multi-threading
    private val QUEUE_CAPACITY = 8
    private var freeQueue: ArrayBlockingQueue<ByteBuffer>? = null
    private var frameQueue: ArrayBlockingQueue<FrameData>? = null
    private var compressorThread: Thread? = null
    private var compressionOutputBuffer: ByteBuffer? = null
    
    private var currentFile: File? = null
    private var lensShadingMapSaved = false

    private class FrameData(
        val timestamp: Long,
        val shutter: Long,
        val iso: Int,
        val focus: Float,
        val frameIndex: Int,
        val awbR: Float,
        val awbGEven: Float,
        val awbGOdd: Float,
        val awbB: Float,
        val postRawBoost: Int,
        val expectedBytes: Int,
        val buffer: ByteBuffer?,
        val isPoisonPill: Boolean = false
    )

    fun isRecording(): Boolean = isRecording || fileChannel != null || compressorThread?.isAlive == true
    
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
    @Volatile var livePostRawBoost = 100
    
    fun prepareEnginePipeline() {
        handlerThread = HandlerThread("RawSpoolerThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
    }
    
    fun getEngineHandler(): Handler? = handler
    
    fun getDroppedFrames(): Int = droppedFramesCount
    
    fun startRecording(file: File, fps: Int, dngOrientation: Int, useGoogleMetadata: Boolean, singleFrame: Boolean = false) {
        currentFile = file
        lensShadingMapSaved = false
        isSingleFrame = singleFrame
        frameCount = 0
        lastImageTimestamp = 0L
        droppedFramesCount = 0
        expectedFrameDurationNs = 1_000_000_000L / fps.toLong()
        fileChannel = FileOutputStream(file, false).channel
        
        val globalHeaderBuffer = ByteBuffer.allocateDirect(1024)
        globalHeaderBuffer.order(ByteOrder.LITTLE_ENDIAN)
        globalHeaderBuffer.put("AYUSHRC1".toByteArray()) // Compressed format v1
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
        
        freeQueue = ArrayBlockingQueue(QUEUE_CAPACITY)
        frameQueue = ArrayBlockingQueue(QUEUE_CAPACITY)
        isRecording = true
        
        compressorThread = thread(start = true, name = "CompressorThread") {
            runCompressorLoop()
        }
    }
    
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        
        try {
            frameQueue?.put(FrameData(0, 0, 0, 0f, 0, 0f, 0f, 0f, 0f, 0, 0, null, true))
        } catch (e: Exception) {}
        
        handler?.post {
            compressorThread?.join(2000)
            fileChannel?.close()
            fileChannel = null
            
            freeQueue?.clear()
            frameQueue?.clear()
            compressionOutputBuffer = null
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
        
        if (!isRecording || fileChannel == null || freeQueue == null || frameQueue == null) {
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
        
        val cropStartRow = ((sourceHeight - height) / 2) and -2
        val startOffset = cropStartRow * rowStride
        val expectedBytes = height * rowStride
        val actualBytes = pixelBuffer.remaining()
        
        if (frameCount == 0) {
            // Allocate the buffer pool
            for (i in 0 until QUEUE_CAPACITY) {
                val buf = ByteBuffer.allocateDirect(expectedBytes)
                buf.order(ByteOrder.nativeOrder())
                freeQueue!!.add(buf)
            }
            
            val strideBuffer = ByteBuffer.allocateDirect(4)
            strideBuffer.order(ByteOrder.LITTLE_ENDIAN)
            strideBuffer.putInt(rowStride)
            strideBuffer.position(0)
            fileChannel?.position(16)
            fileChannel?.write(strideBuffer)
            fileChannel?.position(1024)
        }
        
        // Take a free buffer
        val inputBuf = freeQueue!!.poll()
        if (inputBuf == null) {
            Log.w(TAG, "Frame dropped! Compressor queue full.")
            droppedFramesCount++
            image.close()
            return@OnImageAvailableListener
        }
        
        inputBuf.clear()
        
        val originalLimit = pixelBuffer.limit()
        if (actualBytes >= startOffset + expectedBytes) {
            pixelBuffer.position(startOffset)
            pixelBuffer.limit(startOffset + expectedBytes)
            inputBuf.put(pixelBuffer)
        } else if (actualBytes > startOffset) {
            pixelBuffer.position(startOffset)
            inputBuf.put(pixelBuffer)
        }
        
        pixelBuffer.limit(originalLimit)
        
        if (inputBuf.position() < expectedBytes) {
            val diff = expectedBytes - inputBuf.position()
            for (i in 0 until diff) inputBuf.put(0.toByte())
        }
        inputBuf.flip()
        
        val frameData = FrameData(
            timestamp = ts,
            shutter = liveShutter,
            iso = liveIso,
            focus = liveFocus,
            frameIndex = frameCount,
            awbR = liveAwbR,
            awbGEven = liveAwbG_even,
            awbGOdd = liveAwbG_odd,
            awbB = liveAwbB,
            postRawBoost = livePostRawBoost,
            expectedBytes = expectedBytes,
            buffer = inputBuf
        )
        
        try {
            frameQueue!!.put(frameData)
        } catch (e: Exception) {
            freeQueue!!.put(inputBuf)
        }
        
        image.close()
        frameCount++
        
        if (isSingleFrame) {
            stopRecording()
        }
    }

    private fun writeFrameHeader(frame: FrameData, compressedSize: Int, originalSize: Int) {
        val frameHeader = ByteBuffer.allocateDirect(56)
        frameHeader.order(ByteOrder.LITTLE_ENDIAN)
        frameHeader.putLong(frame.timestamp)
        frameHeader.putLong(frame.shutter)
        frameHeader.putInt(frame.iso)
        frameHeader.putFloat(frame.focus)
        frameHeader.putInt(frame.frameIndex)
        frameHeader.putFloat(frame.awbR)
        frameHeader.putFloat(frame.awbGEven)
        frameHeader.putFloat(frame.awbGOdd)
        frameHeader.putFloat(frame.awbB)
        frameHeader.putInt(frame.postRawBoost)
        frameHeader.putInt(compressedSize)
        frameHeader.putInt(originalSize)
        frameHeader.position(0)
        fileChannel?.write(frameHeader)
    }

    private fun runCompressorLoop() {
        while (true) {
            val frame = frameQueue?.take() ?: break
            if (frame.isPoisonPill) {
                break
            }
            
            if (compressionOutputBuffer == null) {
                val compressBound = RawCompressor.compressBound(frame.expectedBytes)
                compressionOutputBuffer = ByteBuffer.allocateDirect(compressBound)
                compressionOutputBuffer!!.order(ByteOrder.nativeOrder())
                Log.d(TAG, "LZ4 thread started, buffer bound: $compressBound")
            }
            
            val outBuf = compressionOutputBuffer!!
            outBuf.clear()
            val compressedSize = RawCompressor.compress(frame.buffer!!, frame.expectedBytes, outBuf)
            
            if (compressedSize <= 0) {
                Log.w(TAG, "LZ4 compression failed for frame ${frame.frameIndex}")
                frame.buffer.position(0)
                writeFrameHeader(frame, frame.expectedBytes, frame.expectedBytes)
                frame.buffer.limit(frame.expectedBytes)
                fileChannel?.write(frame.buffer)
            } else {
                writeFrameHeader(frame, compressedSize, frame.expectedBytes)
                outBuf.position(0)
                outBuf.limit(compressedSize)
                fileChannel?.write(outBuf)
            }
            
            // Return buffer back to the pool
            freeQueue?.put(frame.buffer)
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
