package com.example.rawrecorder.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
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
import kotlin.math.max

object VideoRendererService {
    private const val TAG = "VideoRendererService"

    // Compose state variables for UI exposure
    var isRendering by mutableStateOf(false)
    var renderingPath by mutableStateOf("")
    var currentPhase by mutableStateOf("")
    var progressFraction by mutableStateOf(0f)

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

        // Reset state
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

            // Generate preview image inside the DNG folder
            val previewFile = File(outputDir, "preview.jpg")
            val previewSuccess = generatePreview(file, previewFile)
            Log.d(TAG, "Preview generation success: $previewSuccess")

            // Scan files using MediaScannerConnection to register with MediaStore
            val outputFiles = outputDir.listFiles() ?: emptyArray()
            val paths = outputFiles.map { it.absolutePath }.toTypedArray()
            MediaScannerConnection.scanFile(context, paths, null, null)

            // Delete original .ayushraw file
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

    private fun generatePreview(inputFile: File, outputFile: File): Boolean {
        try {
            FileInputStream(inputFile).use { fis ->
                val globalHeader = ByteArray(512)
                var bytesRead = 0
                while (bytesRead < 512) {
                    val r = fis.read(globalHeader, bytesRead, 512 - bytesRead)
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
                
                val blPattern = IntArray(4)
                for (i in 0 until 4) blPattern[i] = buffer.int
                val whiteLevel = buffer.int
                
                val cropLeft = buffer.int
                val cropTop = buffer.int
                val cropWidth = buffer.int
                val cropHeight = buffer.int
                
                buffer.position(360)
                val sourceHeightVal = buffer.int
                
                val sourceHeight = if (sourceHeightVal in 1..10000) sourceHeightVal else height
                val payloadSize = sourceHeight * rowStride
                
                // Read first frame header (48 bytes)
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
                
                // Read payload of the first frame
                val payload = ByteArray(payloadSize)
                var plBytesRead = 0
                while (plBytesRead < payloadSize) {
                    val r = fis.read(payload, plBytesRead, payloadSize - plBytesRead)
                    if (r < 0) return false
                    plBytesRead += r
                }
                
                // Unpack RAW10 payload
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
                
                // Pre-normalize and apply AWB
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
                
                // Color Matrix construction
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
                
                // Ensure default/fallback matrix is identity if invalid
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
                
                // XYZ D50 to Rec.709 D65
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

                // Downscaled bilinear demosaic for preview
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
                        
                        if (ch == 0) { // Red pixel
                            r = normGrid[idxVal]
                            g = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX] + normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.25f
                            b = (normGrid[prevYWidth + prevX] + normGrid[prevYWidth + nextX] + normGrid[nextYWidth + prevX] + normGrid[nextYWidth + nextX]) * 0.25f
                        } else if (ch == 2) { // Blue pixel
                            b = normGrid[idxVal]
                            g = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX] + normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.25f
                            r = (normGrid[prevYWidth + prevX] + normGrid[prevYWidth + nextX] + normGrid[nextYWidth + prevX] + normGrid[nextYWidth + nextX]) * 0.25f
                        } else { // Green pixel
                            g = normGrid[idxVal]
                            if (rowHasRed) {
                                r = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX]) * 0.5f
                                b = (normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.5f
                            } else {
                                r = (normGrid[prevYWidth + x] + normGrid[nextYWidth + x]) * 0.5f
                                b = (normGrid[currYWidth + prevX] + normGrid[currYWidth + nextX]) * 0.5f
                            }
                        }
                        
                        // Color correction
                        var rout = combinedMatrix[0]*r + combinedMatrix[1]*g + combinedMatrix[2]*b
                        var gout = combinedMatrix[3]*r + combinedMatrix[4]*g + combinedMatrix[5]*b
                        var bout = combinedMatrix[6]*r + combinedMatrix[7]*g + combinedMatrix[8]*b
                        
                        // Simple Gamma 2.2 approximation
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
