package com.example.rawrecorder

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.example.rawrecorder.gallery.DngExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PhotoCaptureEngine {
    private const val TAG = "PhotoCaptureEngine"

    fun capturePhoto(
        context: Context,
        rawSpoolerEngine: RawSpoolerEngine,
        fps: Int,
        orientation: Int,
        useGoogleMetadata: Boolean,
        triggerSingleCapture: () -> Unit,
        onCaptureComplete: (File) -> Unit
    ) {
        val dir = context.getExternalFilesDir(null) ?: return
        val tempAyushraw = File(dir, "temp_photo_${System.currentTimeMillis()}.ayushraw")
        
        // Start recording to the temp file in single frame mode
        rawSpoolerEngine.startRecording(tempAyushraw, fps, orientation, useGoogleMetadata, singleFrame = true)
        
        // Trigger the single capture on the camera session
        triggerSingleCapture()
        
        // Wait until rawSpoolerEngine has completed capturing the single frame
        CoroutineScope(Dispatchers.Main).launch {
            while (rawSpoolerEngine.isRecording()) {
                kotlinx.coroutines.delay(10)
            }
            
            // Extract the first frame
            CoroutineScope(Dispatchers.IO).launch {
                val outputDir = File(dir, "Photos")
                if (!outputDir.exists()) outputDir.mkdirs()
                
                Log.d(TAG, "Extracting photo from ${tempAyushraw.absolutePath}")
                val extractedFrames = DngExtractor.extractAyushrawToDngs(context, tempAyushraw, outputDir) { progress ->
                    // No-op for progress
                }
                
                if (tempAyushraw.exists()) {
                    tempAyushraw.delete() // Clean up temp file
                }
                
                if (extractedFrames > 0) {
                    val dngFile = File(outputDir, "frame_00000.dng")
                    if (dngFile.exists()) {
                        val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        val rawRecorderDir = File(docDir, "RawRecorder")
                        if (!rawRecorderDir.exists()) rawRecorderDir.mkdirs()
                        
                        val finalFile = File(rawRecorderDir, "Photo_${System.currentTimeMillis()}.dng")
                        val success = dngFile.renameTo(finalFile)
                        if (!success) {
                            try {
                                dngFile.inputStream().use { input ->
                                    finalFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                dngFile.delete()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to copy photo to Documents/RawRecorder", e)
                            }
                        }
                        
                        Log.d(TAG, "Photo saved: ${finalFile.absolutePath}")
                        
                        // Register file with MediaStore/system gallery
                        MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
                        
                        withContext(Dispatchers.Main) {
                            onCaptureComplete(finalFile)
                        }
                    }
                }
            }
        }
    }
}
