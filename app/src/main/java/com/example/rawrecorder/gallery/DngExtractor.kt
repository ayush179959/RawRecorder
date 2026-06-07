package com.example.rawrecorder.gallery

import android.util.Log
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

    suspend fun extractAyushrawToDngs(
        context: android.content.Context,
        file: File,
        outputDir: File,
        onProgress: (Int, Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val globalHeader = ByteArray(512)
        var frameCount = 0

        FileInputStream(file).use { fis ->
            val readHeaderSuccess = readFully(fis, globalHeader)
            if (!readHeaderSuccess || String(globalHeader, 0, 8, Charsets.US_ASCII) != "AYUSHRAW") {
                Log.e(TAG, "Invalid global header")
                return@withContext 0
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

            val blPattern = IntArray(4)
            for (i in 0 until 4) blPattern[i] = buffer.int
            val whiteLevel = buffer.int
            
            val cropLeft = buffer.int
            val cropTop = buffer.int
            val cropWidth = buffer.int
            val cropHeight = buffer.int

            buffer.position(360)
            val sourceHeightVal = buffer.int
            val dngOrientationVal = buffer.int
            
            buffer.position(368)
            val metaMode = buffer.int
            val sensorType = if (buffer.remaining() >= 4) buffer.int else 0
            
            val sourceHeight = if (sourceHeightVal in 1..10000) sourceHeightVal else height
            val dngOrientation = if (dngOrientationVal in intArrayOf(1, 3, 6, 8)) dngOrientationVal else 1

            Log.d(TAG, "Detected: ${width}x${height}, Stride: $rowStride, CFA: $cfaPattern, Depth: $bitDepth-bit, SourceHeight: $sourceHeight, Orientation: $dngOrientation, SensorType: $sensorType")

            val cfaTuple = when (cfaPattern) {
                0 -> intArrayOf(0, 1, 1, 2) // RGGB
                1 -> intArrayOf(1, 0, 2, 1) // GRBG
                2 -> intArrayOf(1, 2, 0, 1) // GBRG
                3 -> intArrayOf(2, 1, 1, 0) // BGGR
                else -> intArrayOf(1, 2, 0, 1)
            }

            val payloadSize = sourceHeight * rowStride
            val totalFrames = if (payloadSize > 0) ((file.length() - 512) / (48 + payloadSize)).toInt() else 1
            val outputBitDepth = 10
            val activeBlackLevel = blPattern
            val activeWhiteLevel = whiteLevel

            val validWidth = width

            val frameHeader = ByteArray(48)
            val payload = ByteArray(payloadSize)

            val sensorSuffix = when (sensorType) {
                1 -> "ultra"
                2 -> "tele"
                3 -> "front"
                else -> "main"
            }

            val assetManager = context.assets
            val hs1Bytes = assetManager.open("profile_hs1_$sensorSuffix.bin").use { it.readBytes() }
            val hs2Bytes = assetManager.open("profile_hs2_$sensorSuffix.bin").use { it.readBytes() }
            val lookBytes = assetManager.open("profile_look_$sensorSuffix.bin").use { it.readBytes() }
            val toneBytes = assetManager.open("profile_tone_$sensorSuffix.bin").use { it.readBytes() }

            while (true) {
                val headerReadSuccess = readFully(fis, frameHeader)
                if (!headerReadSuccess) break

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

                val rGain = max(0.0001f, gRed)
                val gGain = max(0.0001f, (gGreenEven + gGreenOdd) / 2.0f)
                val bGain = max(0.0001f, gBlue)
                val rVal = gGain / rGain
                val bVal = gGain / bGain

                val payloadReadSuccess = readFully(fis, payload)
                if (!payloadReadSuccess) {
                    Log.w(TAG, "Incomplete payload at frame $frameCount")
                    break
                }

                // Pack RAW10 to consecutive 10-bit DNG format (5 bytes per 4 pixels)
                val packedSize = height * (validWidth * 5 / 4)
                val packed = ByteArray(packedSize)
                
                val cropStartRow = ((sourceHeight - height) / 2) and -2
                
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
                        
                        val outB0 = (p0 shr 2) and 0xFF
                        val outB1 = (((p0 and 0x03) shl 6) or ((p1 shr 4) and 0x3F)) and 0xFF
                        val outB2 = (((p1 and 0x0F) shl 4) or ((p2 shr 6) and 0x0F)) and 0xFF
                        val outB3 = (((p2 and 0x3F) shl 2) or ((p3 shr 8) and 0x03)) and 0xFF
                        val outB4 = p3 and 0xFF
                        
                        packed[outIdx++] = outB0.toByte()
                        packed[outIdx++] = outB1.toByte()
                        packed[outIdx++] = outB2.toByte()
                        packed[outIdx++] = outB3.toByte()
                        packed[outIdx++] = outB4.toByte()
                        
                        inIdx += 5
                    }
                }

                val outFilename = File(outputDir, String.format("frame_%05d.dng", frameCount))
                writeDng(
                    outFilename, packed, validWidth, height, outputBitDepth,
                    cfaTuple, activeBlackLevel, activeWhiteLevel,
                    cropLeft, cropTop, cropWidth, cropHeight,
                    rVal, bVal, iso, shutter, dngOrientation,
                    hs1Bytes, hs2Bytes, toneBytes, lookBytes,
                    sensorType
                )

                frameCount++
                withContext(Dispatchers.Main) {
                    onProgress(frameCount, totalFrames)
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
        hs1Bytes: ByteArray, hs2Bytes: ByteArray, toneBytes: ByteArray, lookBytes: ByteArray,
        sensorType: Int
    ) {
        FileOutputStream(file).use { fos ->
            val buf = ByteBuffer.allocate(16 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN) // large enough for tags
            buf.put("II*\u0000".toByteArray(Charsets.US_ASCII))
            buf.putInt(8)

            val finalCm1: IntArray
            val finalCm2: IntArray
            val finalFm1: IntArray
            val finalFm2: IntArray
            val finalIll1: Int
            val finalIll2: Int
            val finalBaselineExp: IntArray
            val finalNoiseProfile: FloatArray

            when (sensorType) {
                1 -> { // Ultrawide
                    finalCm1 = intArrayOf(14178, 10000, -8720, 10000, 688, 10000, -2895, 10000, 11364, 10000, 1730, 10000, 110, 10000, 814, 10000, 5508, 10000)
                    finalCm2 = intArrayOf(11835, 10000, -5488, 10000, -1032, 10000, -3371, 10000, 11891, 10000, 1648, 10000, -194, 10000, 1235, 10000, 4748, 10000)
                    finalFm1 = intArrayOf(3777, 10000, 4906, 10000, 960, 10000, 1585, 10000, 8136, 10000, 278, 10000, 459, 10000, 16, 10000, 7775, 10000)
                    finalFm2 = intArrayOf(3806, 10000, 4501, 10000, 1336, 10000, 1773, 10000, 7842, 10000, 385, 10000, 652, 10000, 6, 10000, 7593, 10000)
                    finalIll1 = 17
                    finalIll2 = 21
                    finalBaselineExp = intArrayOf(12, 100) // 0.12 EV
                    finalNoiseProfile = floatArrayOf(0.0004338437f, 4.3466407e-6f, 0.0002172339f, 2.2114516e-6f, 0.0004287199f, 4.3406994e-6f)
                }
                2 -> { // Telephoto
                    finalCm1 = intArrayOf(12163, 10000, -5088, 10000, -692, 10000, -2296, 10000, 10998, 10000, 1473, 10000, 211, 10000, 1016, 10000, 4655, 10000)
                    finalCm2 = intArrayOf(8380, 10000, -1926, 10000, -623, 10000, -4094, 10000, 12822, 10000, 1364, 10000, -1193, 10000, 2655, 10000, 3949, 10000)
                    finalFm1 = intArrayOf(4222, 10000, 4040, 10000, 1381, 10000, 1880, 10000, 7702, 10000, 418, 10000, 571, 10000, 5, 10000, 7675, 10000)
                    finalFm2 = intArrayOf(5208, 10000, 3320, 10000, 1116, 10000, 2917, 10000, 6733, 10000, 350, 10000, 1702, 10000, 13, 10000, 6536, 10000)
                    finalIll1 = 17
                    finalIll2 = 21
                    finalBaselineExp = intArrayOf(267, 100) // 2.67 EV
                    finalNoiseProfile = floatArrayOf(0.00017274475f, 6.8975623e-7f, 8.3773375e-5f, 3.2082875e-7f, 0.0001556827f, 5.8859496e-7f)
                }
                3 -> { // Front
                    finalCm1 = intArrayOf(20174, 10000, -5979, 10000, -11229, 10000, -19432, 10000, 43771, 10000, -21631, 10000, -2889, 10000, 5882, 10000, 8368, 10000)
                    finalCm2 = intArrayOf(12128, 10000, -5577, 10000, -1067, 10000, -3077, 10000, 11648, 10000, 1599, 10000, -121, 10000, 1501, 10000, 5141, 10000)
                    finalFm1 = intArrayOf(5917, 10000, -885, 10000, 4611, 10000, 2729, 10000, 2453, 10000, 4819, 10000, 485, 10000, -4309, 10000, 12075, 10000)
                    finalFm2 = intArrayOf(3638, 10000, 4646, 10000, 1359, 10000, 1621, 10000, 7989, 10000, 390, 10000, 543, 10000, 32, 10000, 7676, 10000)
                    finalIll1 = 17
                    finalIll2 = 21
                    finalBaselineExp = intArrayOf(0, 100) // 0.0 EV
                    finalNoiseProfile = floatArrayOf(2.0539945e-5f, 2.1160035e-7f, 1.4857906e-5f, 1.2181445e-7f, 2.0451544e-5f, 2.11745e-7f)
                }
                else -> { // Main
                    finalCm1 = intArrayOf(11234, 10000, -5774, 10000, 83, 10000, -3535, 10000, 12410, 10000, 1210, 10000, -303, 10000, 2176, 10000, 5922, 10000)
                    finalCm2 = intArrayOf(10662, 10000, -4641, 10000, -954, 10000, -3284, 10000, 11970, 10000, 1451, 10000, -170, 10000, 1989, 10000, 5182, 10000)
                    finalFm1 = intArrayOf(4078, 10000, 4619, 10000, 946, 10000, 2358, 10000, 7358, 10000, 284, 10000, 1183, 10000, 4, 10000, 7063, 10000)
                    finalFm2 = intArrayOf(3666, 10000, 4641, 10000, 1335, 10000, 1639, 10000, 7979, 10000, 383, 10000, 477, 10000, 42, 10000, 7733, 10000)
                    finalIll1 = 17
                    finalIll2 = 21
                    finalBaselineExp = intArrayOf(5, 100) // 0.05 EV
                    finalNoiseProfile = floatArrayOf(2.0539945e-5f, 2.1160035e-7f, 1.4857906e-5f, 1.2181445e-7f, 2.0451544e-5f, 2.11745e-7f)
                }
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
            
            // Exposure time in seconds (safety check for overflow)
            val expSec = shutter.toDouble() / 1_000_000_000.0
            val expDen = 1_000_000
            val expNum = (expSec * expDen).toInt()
            tags.add(DngTag(33434, 'r', 1, intArrayOf(expNum, expDen))) // ExposureTime
            tags.add(DngTag(34855, 'H', 1, intArrayOf(iso))) // ISOSpeedRatings
            
            tags.add(DngTag(271, 's', 7, "Google\u0000")) // Make
            tags.add(DngTag(272, 's', 12, "Pixel 6 Pro\u0000")) // Model
            tags.add(DngTag(50706, 'B', 4, intArrayOf(1, 4, 0, 0))) // DNGVersion
            tags.add(DngTag(50707, 'B', 4, intArrayOf(1, 1, 0, 0))) // DNGBackwardVersion
            tags.add(DngTag(50708, 's', 19, "Google Pixel 6 Pro\u0000")) // UniqueCameraModel
            
            tags.add(DngTag(50711, 'H', 1, intArrayOf(1))) // CFALayout
            tags.add(DngTag(50713, 'H', 2, intArrayOf(2, 2))) // BlackLevelRepeatDim
            tags.add(DngTag(50714, 'H', 4, blackLevel)) // BlackLevel
            tags.add(DngTag(50717, 'I', 1, intArrayOf(whiteLevel))) // WhiteLevel
            tags.add(DngTag(50719, 'r', 2, intArrayOf(0, 1, 0, 1))) // DefaultCropOrigin
            tags.add(DngTag(50720, 'r', 2, intArrayOf(width, 1, height, 1))) // DefaultCropSize
            tags.add(DngTag(50721, 'S', 9, finalCm1)) // ColorMatrix1
            tags.add(DngTag(50722, 'S', 9, finalCm2)) // ColorMatrix2
            tags.add(DngTag(50728, 'r', 3, intArrayOf((rVal * 1000000).toInt(), 1000000, 1, 1, (bVal * 1000000).toInt(), 1000000))) // AsShotNeutral
            tags.add(DngTag(50730, 'S', 1, finalBaselineExp)) // BaselineExposure
            tags.add(DngTag(50778, 'H', 1, intArrayOf(finalIll1))) // CalibrationIlluminant1
            tags.add(DngTag(50779, 'H', 1, intArrayOf(finalIll2))) // CalibrationIlluminant2
            tags.add(DngTag(50829, 'I', 4, intArrayOf(0, 0, height, width))) // ActiveArea
            tags.add(DngTag(50964, 'S', 9, finalFm1)) // ForwardMatrix1
            tags.add(DngTag(50965, 'S', 9, finalFm2)) // ForwardMatrix2

            // Core Metadata Tags (to align with Pixel Camera DNG behavior)
            tags.add(DngTag(50727, 'r', 3, intArrayOf(1, 1, 1, 1, 1, 1))) // AnalogBalance (1,1,1)
            tags.add(DngTag(50731, 'r', 1, intArrayOf(1, 1))) // BaselineNoise
            tags.add(DngTag(50732, 'r', 1, intArrayOf(1, 1))) // BaselineSharpness
            tags.add(DngTag(51110, 'I', 1, intArrayOf(1))) // DefaultBlackRender
            
            val uniqueId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
            tags.add(DngTag(50781, 'B', 16, uniqueId)) // RawDataUniqueID
            
            val noiseBytes = ByteBuffer.allocate(6 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (f in finalNoiseProfile) putFloat(f)
            }.array()
            tags.add(DngTag(51041, 'f', 6, noiseBytes)) // NoiseProfile

            tags.add(DngTag(50932, 's', 10, "com.adobe\u0000")) // ProfileCalibrationSignature
            tags.add(DngTag(50936, 's', 15, "Adobe Standard\u0000")) // ProfileName
            tags.add(DngTag(50937, 'I', 3, intArrayOf(90, 30, 1))) // ProfileHueSatMapDims
            tags.add(DngTag(50938, 'f', hs1Bytes.size / 4, hs1Bytes)) // ProfileHueSatMapData1
            tags.add(DngTag(50939, 'f', hs2Bytes.size / 4, hs2Bytes)) // ProfileHueSatMapData2
            tags.add(DngTag(50940, 'f', toneBytes.size / 4, toneBytes)) // ProfileToneCurve
            tags.add(DngTag(50941, 'I', 1, intArrayOf(0))) // ProfileEmbedPolicy
            tags.add(DngTag(50981, 'I', 3, intArrayOf(36, 8, 16))) // ProfileLookTableDims
            tags.add(DngTag(50982, 'f', lookBytes.size / 4, lookBytes)) // ProfileLookTableData

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
                    'S' -> 10
                    'f' -> 11
                    else -> 1
                }
                buf.putShort(typeCode.toShort())
                
                var count = t.count
                if (t.type == 'S' || t.type == 'r') count /= 2 // For rationals, count is number of rationals
                
                buf.putInt(t.count) // actually number of values
                
                val byteSize = when (t.type) {
                    'B' -> t.count
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
            
            buf.putInt(0) // next IFD offset
            
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
