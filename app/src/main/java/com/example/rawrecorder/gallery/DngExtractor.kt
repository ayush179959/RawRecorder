package com.example.rawrecorder.gallery

import android.util.Log
import com.example.rawrecorder.RawCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

object DngExtractor {
    private const val TAG = "DngExtractor"

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

    private fun String.toTiffString(): String {
        return if (this.endsWith("\u0000")) this else this + "\u0000"
    }

    private fun loadLensShadingMap(ayushrawFile: File): Pair<IntArray, ByteBuffer>? {
        val lsmFile = File(ayushrawFile.parentFile, ayushrawFile.nameWithoutExtension + ".lsm")
        if (!lsmFile.exists()) return null
        try {
            val fis = java.io.FileInputStream(lsmFile)
            val header = ByteArray(16)
            if (fis.read(header) != 16) return null
            val magic = String(header, 0, 8, Charsets.US_ASCII)
            if (magic != "AYUSHLSM") return null
            val bb = ByteBuffer.wrap(header, 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            val width = bb.int
            val height = bb.int
            val dataSize = width * height * 16 // 4 floats * 4 bytes
            val data = ByteArray(dataSize)
            var read = 0
            while (read < dataSize) {
                val r = fis.read(data, read, dataSize - read)
                if (r < 0) break
                read += r
            }
            val buf = ByteBuffer.allocateDirect(dataSize).order(ByteOrder.nativeOrder())
            buf.put(data)
            buf.position(0)
            return Pair(intArrayOf(width, height), buf)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LSM", e)
            return null
        }
    }

    suspend fun extractAyushrawToDngs(
        context: android.content.Context,
        file: File,
        outputDir: File,
        onProgress: (Float) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val globalHeader = ByteArray(1024)
        var frameCount = 0

        FileInputStream(file).use { fis ->
            val readHeaderSuccess = readFully(fis, globalHeader)
            val magic = String(globalHeader, 0, 8, Charsets.US_ASCII)
            val isCompressed = magic == "AYUSHRC1"
            if (!readHeaderSuccess || (magic != "AYUSHRAW" && magic != "AYUSHRC1")) {
                Log.e(TAG, "Invalid global header: magic=$magic")
                return@withContext 0
            }
            Log.d(TAG, "Format: $magic (compressed=$isCompressed)")

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
            
            val baselineExp = IntArray(2)
            baselineExp[0] = buffer.int
            baselineExp[1] = buffer.int
            
            val noiseProfile = FloatArray(8)
            for (i in 0 until 8) noiseProfile[i] = buffer.float
            
            val lensIntrinsic = FloatArray(5)
            for (i in 0 until 5) lensIntrinsic[i] = buffer.float
            
            val lensDistortion = FloatArray(5)
            for (i in 0 until 5) lensDistortion[i] = buffer.float
            
            val lensAperture = buffer.float
            val lensFocalLength = buffer.float
            
            val makeBytes = ByteArray(32)
            buffer.get(makeBytes)
            var makeLen = 0
            while (makeLen < 32 && makeBytes[makeLen] != 0.toByte()) makeLen++
            val deviceMake = String(makeBytes, 0, makeLen, Charsets.US_ASCII).trim()
            
            val modelBytes = ByteArray(32)
            buffer.get(modelBytes)
            var modelLen = 0
            while (modelLen < 32 && modelBytes[modelLen] != 0.toByte()) modelLen++
            val deviceModel = String(modelBytes, 0, modelLen, Charsets.US_ASCII).trim()
            
            val preWidth = buffer.int
            val preHeight = buffer.int
            
            val sourceHeight = if (sourceHeightVal in 1..10000) sourceHeightVal else height
            val dngOrientation = if (dngOrientationVal in intArrayOf(1, 3, 6, 8)) dngOrientationVal else 1

            Log.d(TAG, "Detected: ${width}x${height}, Stride: $rowStride, CFA: $cfaPattern, Depth: $bitDepth-bit, PreCorrect: ${preWidth}x${preHeight}, Make: $deviceMake, Model: $deviceModel")

            val cfaTuple = when (cfaPattern) {
                0 -> intArrayOf(0, 1, 1, 2) // RGGB
                1 -> intArrayOf(1, 0, 2, 1) // GRBG
                2 -> intArrayOf(1, 2, 0, 1) // GBRG
                3 -> intArrayOf(2, 1, 1, 0) // BGGR
                else -> intArrayOf(1, 2, 0, 1) // Default to GBRG
            }
            
            val payloadSize = height * rowStride
            val totalFrames = if (!isCompressed && payloadSize > 0) ((file.length() - 1024) / (48 + payloadSize)).toInt() else -1
            val outputBitDepth = 16
            val activeBlackLevel = blPattern
            val activeWhiteLevel = whiteLevel
            val payload = ByteArray(payloadSize)
            
            // Allocate decompression buffers once for reuse (compressed files only)
            var compressedPayload: ByteArray? = null
            var compressedDirectBuf: ByteBuffer? = null
            var decompressedDirectBuf: ByteBuffer? = null
            if (isCompressed) {
                compressedPayload = ByteArray(payloadSize * 2)
                compressedDirectBuf = ByteBuffer.allocateDirect(payloadSize * 2)
                compressedDirectBuf.order(ByteOrder.nativeOrder())
                decompressedDirectBuf = ByteBuffer.allocateDirect(payloadSize)
                decompressedDirectBuf.order(ByteOrder.nativeOrder())
            }

            var frameCount = 0
            val frameHeaderSize = if (isCompressed) 56 else 48
            val validWidth = if (rowStride > 0) rowStride * 4 / 5 else width

            while (true) {
                var frameHeader = ByteArray(frameHeaderSize)
                if (!readFully(fis, frameHeader)) {
                    break
                }
                
                val frameBuf = ByteBuffer.wrap(frameHeader).order(ByteOrder.LITTLE_ENDIAN)
                val timestamp = frameBuf.long
                val shutter = frameBuf.long
                val iso = frameBuf.int
                val focus = frameBuf.float
                val frameIdx = frameBuf.int
                val rVal = frameBuf.float
                val gEven = frameBuf.float
                val gOdd = frameBuf.float
                val bVal = frameBuf.float
                val postRawBoost = frameBuf.int
                
                var compressedSize = 0
                var originalSize = 0
                if (isCompressed) {
                    compressedSize = frameBuf.int
                    originalSize = frameBuf.int
                    
                    if (compressedSize <= 0 || compressedSize > compressedPayload!!.size) {
                        Log.e(TAG, "Invalid compressed size $compressedSize at frame $frameCount")
                        break
                    }
                    if (originalSize != payloadSize) {
                        Log.w(TAG, "Original size mismatch: $originalSize vs expected $payloadSize at frame $frameCount")
                    }
                    
                    var cBytesRead = 0
                    while (cBytesRead < compressedSize) {
                        val r = fis.read(compressedPayload, cBytesRead, compressedSize - cBytesRead)
                        if (r < 0) {
                            Log.w(TAG, "Incomplete compressed payload at frame $frameCount")
                            return@withContext frameCount
                        }
                        cBytesRead += r
                    }
                    
                    compressedDirectBuf!!.clear()
                    compressedDirectBuf.put(compressedPayload, 0, compressedSize)
                    compressedDirectBuf.flip()
                    
                    decompressedDirectBuf!!.clear()
                    val decompressedBytes = RawCompressor.decompress(
                        compressedDirectBuf, compressedSize,
                        decompressedDirectBuf, originalSize
                    )
                    
                    if (decompressedBytes <= 0) {
                        Log.e(TAG, "LZ4 decompression failed at frame $frameCount")
                        break
                    }
                    
                    // Copy decompressed data into payload ByteArray
                    decompressedDirectBuf.position(0)
                    decompressedDirectBuf.limit(decompressedBytes)
                    decompressedDirectBuf.get(payload, 0, decompressedBytes)
                } else {
                    // Uncompressed: read payload directly
                    val payloadReadSuccess = readFully(fis, payload)
                    if (!payloadReadSuccess) {
                        Log.w(TAG, "Incomplete payload at frame $frameCount")
                        break
                    }
                }

                // Write 16-bit uncompressed DNG format (2 bytes per pixel)
                val packedSize = height * validWidth * 2
                val packed = ByteArray(packedSize)
                
                val cropStartRow = 0
                
                var inIdx = 0
                var outIdx = 0
                for (y in 0 until height) {
                    inIdx = (y + cropStartRow) * rowStride
                    for (x in 0 until validWidth step 4) {
                        val b0 = payload[inIdx].toInt() and 0xFF
                        val b1 = payload[inIdx + 1].toInt() and 0xFF
                        val b2 = payload[inIdx + 2].toInt() and 0xFF
                        val b3 = payload[inIdx + 3].toInt() and 0xFF
                        val b4 = payload[inIdx + 4].toInt() and 0xFF
                        
                        val p0 = (b0 shl 2) or (b4 and 0x03)
                        val p1 = (b1 shl 2) or ((b4 shr 2) and 0x03)
                        val p2 = (b2 shl 2) or ((b4 shr 4) and 0x03)
                        val p3 = (b3 shl 2) or ((b4 shr 6) and 0x03)
                        
                        // Write as 16-bit little-endian
                        packed[outIdx++] = (p0 and 0xFF).toByte()
                        packed[outIdx++] = ((p0 shr 8) and 0xFF).toByte()
                        
                        packed[outIdx++] = (p1 and 0xFF).toByte()
                        packed[outIdx++] = ((p1 shr 8) and 0xFF).toByte()
                        
                        packed[outIdx++] = (p2 and 0xFF).toByte()
                        packed[outIdx++] = ((p2 shr 8) and 0xFF).toByte()
                        
                        packed[outIdx++] = (p3 and 0xFF).toByte()
                        packed[outIdx++] = ((p3 shr 8) and 0xFF).toByte()
                        
                        inIdx += 5
                    }
                }

                val outFilename = File(outputDir, String.format("frame_%05d.dng", frameCount))
                writeDng(
                    outFilename, packed, validWidth, height, outputBitDepth,
                    cfaTuple, activeBlackLevel, activeWhiteLevel,
                    cropLeft, cropTop, cropWidth, cropHeight,
                    rVal, bVal, iso, shutter, dngOrientation,
                    cm1, cm2, fm1, fm2, cal1, cal2,
                    ill1, ill2, baselineExp, noiseProfile,
                    lensIntrinsic, lensDistortion, lensAperture, lensFocalLength,
                    deviceMake, deviceModel, preWidth, preHeight,
                    postRawBoost
                )

                frameCount++
                withContext(Dispatchers.Main) {
                    val progressFraction = fis.channel.position().toFloat() / file.length().toFloat()
                    onProgress(progressFraction.coerceIn(0f, 1f))
                }
            }
        }
        return@withContext frameCount
    }

    private fun writeDng(
        file: File, pixelData: ByteArray, width: Int, height: Int, bitDepth: Int,
        cfaTuple: IntArray, blackLevel: IntArray, whiteLevel: Int,
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
        rVal: Float, bVal: Float, iso: Int, shutter: Long, dngOrientation: Int,
        cm1: IntArray, cm2: IntArray, fm1: IntArray, fm2: IntArray,
        cal1: IntArray, cal2: IntArray, ill1: Int, ill2: Int,
        baselineExp: IntArray, noiseProfile: FloatArray,
        lensIntrinsic: FloatArray, lensDistortion: FloatArray,
        lensAperture: Float, lensFocalLength: Float,
        deviceMake: String, deviceModel: String,
        preWidth: Int, preHeight: Int,
        postRawBoost: Int
    ) {
        FileOutputStream(file).use { fos ->
            val buf = ByteBuffer.allocate(16 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("II*\u0000".toByteArray(Charsets.US_ASCII))
            buf.putInt(8)

            // Setup WarpRectilinear opcode
            val fx = lensIntrinsic[0]
            val cx = lensIntrinsic[2]
            val cy = lensIntrinsic[3]
            val k1 = lensDistortion[0]
            val k2 = lensDistortion[1]
            val k3 = lensDistortion[2]
            val p1 = lensDistortion[3]
            val p2 = lensDistortion[4]

            val hasDistortion = (k1 != 0f || k2 != 0f || k3 != 0f || p1 != 0f || p2 != 0f) && fx > 0f && preWidth > 0 && preHeight > 0
            var opcodeBytes: ByteArray? = null

            if (hasDistortion) {
                val mX = max(preWidth.toFloat() - cx, cx)
                val mY = max(preHeight.toFloat() - cy, cy)
                val mSq = mX * mX + mY * mY
                val m = Math.sqrt(mSq.toDouble())
                val fSq = fx * fx

                val conv0 = mSq / fSq
                val conv1 = (mSq * mSq) / (fSq * fSq)
                val conv2 = (mSq * mSq * mSq) / (fSq * fSq * fSq)
                val conv3 = m / fx
                val conv4 = m / fx

                val kr0 = k1 * conv0
                val kr1 = k2 * conv1
                val kr2 = k3 * conv2
                val kr3 = 0.0
                val kt0 = p1 * conv3
                val kt1 = p2 * conv4

                val cxNorm = cx.toDouble() / preWidth
                val cyNorm = cy.toDouble() / preHeight

                val opcodeBuf = ByteBuffer.allocate(88).order(ByteOrder.BIG_ENDIAN)
                opcodeBuf.putInt(1) // Count of opcodes = 1
                opcodeBuf.putInt(1) // Opcode ID = 1 (WarpRectilinear)
                opcodeBuf.putInt(0x01030000) // DNG Version = 1.3.0.0
                opcodeBuf.putInt(0) // Flags = 0
                opcodeBuf.putInt(68) // Size = 68
                opcodeBuf.putInt(1) // N = 1
                opcodeBuf.putDouble(kr0.toDouble())
                opcodeBuf.putDouble(kr1.toDouble())
                opcodeBuf.putDouble(kr2.toDouble())
                opcodeBuf.putDouble(kr3.toDouble())
                opcodeBuf.putDouble(kt0.toDouble())
                opcodeBuf.putDouble(kt1.toDouble())
                opcodeBuf.putDouble(cxNorm.toDouble())
                opcodeBuf.putDouble(cyNorm.toDouble())
                opcodeBytes = opcodeBuf.array()
            }

            val tags = mutableListOf<DngTag>()
            tags.add(DngTag(254, 'I', 1, intArrayOf(0))) // NewSubfileType
            tags.add(DngTag(256, 'I', 1, intArrayOf(width))) // ImageWidth
            tags.add(DngTag(257, 'I', 1, intArrayOf(height))) // ImageLength
            tags.add(DngTag(258, 'H', 1, intArrayOf(bitDepth))) // BitsPerSample
            tags.add(DngTag(259, 'H', 1, intArrayOf(1))) // Compression
            tags.add(DngTag(262, 'H', 1, intArrayOf(32803))) // PhotometricInterpretation (CFA)
            tags.add(DngTag(273, 'I', 1, intArrayOf(0))) // StripOffsets (placeholder)
            tags.add(DngTag(274, 'H', 1, intArrayOf(dngOrientation))) // Orientation
            tags.add(DngTag(277, 'H', 1, intArrayOf(1))) // SamplesPerPixel
            tags.add(DngTag(278, 'I', 1, intArrayOf(height))) // RowsPerStrip
            tags.add(DngTag(279, 'I', 1, intArrayOf(pixelData.size))) // StripByteCounts
            tags.add(DngTag(282, 'r', 1, intArrayOf(300, 1))) // XResolution
            tags.add(DngTag(283, 'r', 1, intArrayOf(300, 1))) // YResolution
            tags.add(DngTag(284, 'H', 1, intArrayOf(1))) // PlanarConfiguration
            tags.add(DngTag(305, 's', 16, "RawRecorder\u0000\u0000\u0000\u0000\u0000")) // Software
            tags.add(DngTag(33421, 'H', 2, intArrayOf(2, 2))) // CFARepeatPatternDim
            tags.add(DngTag(33422, 'B', 4, cfaTuple)) // CFAPattern
            
            val expSec = shutter.toDouble() / 1_000_000_000.0
            val expDen = 1_000_000
            val expNum = (expSec * expDen).toInt()
            tags.add(DngTag(33434, 'r', 1, intArrayOf(expNum, expDen))) // ExposureTime
            tags.add(DngTag(34855, 'H', 1, intArrayOf(iso))) // ISOSpeedRatings
            
            val finalMake = deviceMake.ifEmpty { "Google" }
            val finalModel = deviceModel.ifEmpty { "Pixel" }
            tags.add(DngTag(50706, 'B', 4, intArrayOf(1, 4, 0, 0))) // DNGVersion
            
            val uniqueModel = (finalMake + " " + finalModel).toTiffString()
            tags.add(DngTag(50708, 's', uniqueModel.length, uniqueModel)) // UniqueCameraModel
            
            tags.add(DngTag(50713, 'H', 2, intArrayOf(2, 2))) // BlackLevelRepeatDim
            tags.add(DngTag(50714, 'H', 4, blackLevel)) // BlackLevel
            tags.add(DngTag(50717, 'I', 1, intArrayOf(whiteLevel))) // WhiteLevel
            
            tags.add(DngTag(50721, 'S', 9, cm1)) // ColorMatrix1
            tags.add(DngTag(50722, 'S', 9, cm2)) // ColorMatrix2
            tags.add(DngTag(50723, 'S', 9, cal1)) // CameraCalibration1
            tags.add(DngTag(50724, 'S', 9, cal2)) // CameraCalibration2
            
            tags.add(DngTag(50728, 'r', 3, intArrayOf((rVal * 1000000).toInt(), 1000000, 1, 1, (bVal * 1000000).toInt(), 1000000))) // AsShotNeutral
            tags.add(DngTag(50778, 'H', 1, intArrayOf(ill1))) // CalibrationIlluminant1
            tags.add(DngTag(50779, 'H', 1, intArrayOf(ill2))) // CalibrationIlluminant2
            tags.add(DngTag(50829, 'I', 4, intArrayOf(0, 0, height, width))) // ActiveArea
            tags.add(DngTag(50964, 'S', 9, fm1)) // ForwardMatrix1
            tags.add(DngTag(50965, 'S', 9, fm2)) // ForwardMatrix2

            tags.add(DngTag(50727, 'r', 3, intArrayOf(1, 1, 1, 1, 1, 1))) // AnalogBalance (1,1,1)
            
            // Combine baselineExp and postRawBoost into a single BaselineExposure tag
            val baseExpVal = baselineExp[0].toDouble() / baselineExp[1].toDouble()
            val boostMultiplier = postRawBoost.toDouble() / 100.0
            val boostStops = if (boostMultiplier > 0.0) Math.log(boostMultiplier) / Math.log(2.0) else 0.0
            val totalBaselineExp = baseExpVal + boostStops
            val bExpNum = (totalBaselineExp * 100.0).toInt()
            val bExpDen = 100
            tags.add(DngTag(50730, 'S', 1, intArrayOf(bExpNum, bExpDen))) // BaselineExposure

            val uniqueId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
            tags.add(DngTag(50781, 'B', 16, uniqueId)) // RawDataUniqueID
            
            if (lensFocalLength > 0f && lensAperture > 0f) {
                val lensInfo = intArrayOf(
                    (lensFocalLength * 100).toInt(), 100,
                    (lensFocalLength * 100).toInt(), 100,
                    (lensAperture * 100).toInt(), 100,
                    (lensAperture * 100).toInt(), 100
                )
                tags.add(DngTag(50827, 'S', 4, lensInfo)) // LensInfo
            }

            if (opcodeBytes != null) {
                tags.add(DngTag(51009, 'c', opcodeBytes.size, opcodeBytes)) // OpcodeList2
            }

            tags.sortBy { it.id }

            val ifdSize = 2 + tags.size * 12 + 4
            val pixelDataOffset = 8 + ifdSize
            
            for (t in tags) {
                if (t.id == 273) t.dataInt = intArrayOf(pixelDataOffset)
            }

            buf.putShort(tags.size.toShort())
            
            val tagDataBlock = ByteBuffer.allocate(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            var currentDataOffset = pixelDataOffset + pixelData.size
            
            for (t in tags) {
                buf.putShort(t.id.toShort())
                val typeCode = when (t.type) {
                    'B' -> 1
                    's' -> 2
                    'H' -> 3
                    'I' -> 4
                    'r' -> 5
                    'c' -> 7
                    'S' -> 10
                    'f' -> 11
                    else -> 1
                }
                buf.putShort(typeCode.toShort())
                
                var count = t.count
                if (t.type == 'S' || t.type == 'r') count /= 2
                
                buf.putInt(t.count)
                
                val byteSize = when (t.type) {
                    'B', 'c' -> t.count
                    's' -> t.count
                    'H' -> t.count * 2
                    'I' -> t.count * 4
                    'f' -> t.count * 4
                    'r', 'S' -> t.count * 8
                    else -> t.count
                }
                
                if (byteSize <= 4) {
                    val startPos = buf.position()
                    if (t.dataBytes != null) {
                        buf.put(t.dataBytes!!)
                    } else if (t.type == 'B' || t.type == 'H' || t.type == 'I') {
                        for (i in 0 until t.count) {
                            if (t.type == 'B') buf.put(t.dataInt!![i].toByte())
                            if (t.type == 'H') buf.putShort(t.dataInt!![i].toShort())
                            if (t.type == 'I') buf.putInt(t.dataInt!![i])
                        }
                    } else if (t.type == 's') {
                        val bytes = t.dataStr!!.toByteArray(Charsets.US_ASCII)
                        buf.put(bytes)
                    }
                    val pad = 4 - (buf.position() - startPos)
                    for (i in 0 until pad) buf.put(0.toByte())
                } else {
                    buf.putInt(currentDataOffset)
                    
                    if (t.dataBytes != null) {
                        tagDataBlock.put(t.dataBytes!!)
                    } else if (t.type == 'r' || t.type == 'S') {
                        for (v in t.dataInt!!) tagDataBlock.putInt(v)
                    } else if (t.type == 's') {
                        val bytes = t.dataStr!!.toByteArray(Charsets.US_ASCII)
                        tagDataBlock.put(bytes)
                        val pad = byteSize - bytes.size
                        for (i in 0 until pad) tagDataBlock.put(0.toByte())
                    } else {
                        for (i in 0 until t.count) {
                            if (t.type == 'B') tagDataBlock.put(t.dataInt!![i].toByte())
                            if (t.type == 'H') tagDataBlock.putShort(t.dataInt!![i].toShort())
                            if (t.type == 'I') tagDataBlock.putInt(t.dataInt!![i])
                        }
                    }
                    currentDataOffset += byteSize
                }
            }
            
            buf.putInt(0)
            
            fos.write(buf.array(), 0, buf.position())
            fos.write(pixelData)
            fos.write(tagDataBlock.array(), 0, tagDataBlock.position())
        }
    }

    class DngTag {
        var id: Int
        var type: Char
        var count: Int
        var dataInt: IntArray? = null
        var dataStr: String? = null
        var dataBytes: ByteArray? = null

        constructor(id: Int, type: Char, count: Int, data: IntArray) {
            this.id = id
            this.type = type
            this.count = count
            this.dataInt = data
        }

        constructor(id: Int, type: Char, count: Int, data: String) {
            this.id = id
            this.type = type
            this.count = count
            this.dataStr = data
        }

        constructor(id: Int, type: Char, count: Int, data: ByteArray) {
            this.id = id
            this.type = type
            this.count = count
            this.dataBytes = data
        }
    }
}
