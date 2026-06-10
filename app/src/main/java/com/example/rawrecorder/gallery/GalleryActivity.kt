package com.example.rawrecorder.gallery

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rawrecorder.ui.theme.RawRecorderTheme
import java.io.File

class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            RawRecorderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    GalleryScreen(this)
                }
            }
        }
    }
}

data class GalleryItem(
    val id: String,
    val displayName: String,
    val rawFile: File?,
    val dngDir: File?,
    val hevcFile: File?,
    val previewFile: File?,
    val sizeString: String,
    val isExtracted: Boolean
)

class GalleryItemBuilder(val id: String) {
    var rawFile: File? = null
    var dngDir: File? = null
    var hevcFile: File? = null
    var previewFile: File? = null
}

fun loadGalleryItems(context: Context): List<GalleryItem> {
    val itemsMap = mutableMapOf<String, GalleryItemBuilder>()
    
    // 1. Scan source folders for .ayushraw files
    val sourceDirs = mutableListOf<File>()
    context.getExternalFilesDir(null)?.let { sourceDirs.add(it) }
    sourceDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
    
    for (dir in sourceDirs) {
        if (!dir.exists()) continue
        val files = dir.listFiles { _, name -> name.endsWith(".ayushraw") } ?: continue
        for (file in files) {
            val id = file.nameWithoutExtension
            val builder = itemsMap.getOrPut(id) { GalleryItemBuilder(id) }
            builder.rawFile = file
        }
    }
    
    // 2. Scan Documents/RawRecorder for extracted folders and HEVC videos
    val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val rawRecorderDir = File(docDir, "RawRecorder")
    val rawRecorderDirs = mutableListOf(rawRecorderDir)
    context.getExternalFilesDir(null)?.let { 
        rawRecorderDirs.add(File(it, "RawRecorder"))
    }
    
    for (rDir in rawRecorderDirs) {
        if (!rDir.exists()) continue
        
        // Scan folders (DNG extracted frames)
        val subDirs = rDir.listFiles { f -> f.isDirectory } ?: continue
        for (subDir in subDirs) {
            val id = subDir.name
            val builder = itemsMap.getOrPut(id) { GalleryItemBuilder(id) }
            builder.dngDir = subDir
            val preview = File(subDir, "preview.jpg")
            if (preview.exists()) {
                builder.previewFile = preview
            }
        }
        
        // Scan files (HEVC rendered videos)
        val mp4Files = rDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: continue
        for (mp4File in mp4Files) {
            val id = mp4File.nameWithoutExtension
            val builder = itemsMap.getOrPut(id) { GalleryItemBuilder(id) }
            builder.hevcFile = mp4File
        }
    }
    
    // 3. Convert to GalleryItem list
    return itemsMap.values.map { builder ->
        val id = builder.id
        val rawFile = builder.rawFile
        val dngDir = builder.dngDir
        val hevcFile = builder.hevcFile
        val previewFile = builder.previewFile
        
        val isExtracted = dngDir != null || hevcFile != null
        val sizeString = if (rawFile != null) {
            "${rawFile.length() / (1024 * 1024)} MB"
        } else if (dngDir != null) {
            val dngCount = dngDir.listFiles { _, name -> name.endsWith(".dng") }?.size ?: 0
            "$dngCount frames"
        } else if (hevcFile != null) {
            "${hevcFile.length() / (1024 * 1024)} MB (Video)"
        } else {
            ""
        }
        
        GalleryItem(
            id = id,
            displayName = id,
            rawFile = rawFile,
            dngDir = dngDir,
            hevcFile = hevcFile,
            previewFile = previewFile,
            sizeString = sizeString,
            isExtracted = isExtracted
        )
    }.sortedByDescending { item ->
        item.rawFile?.lastModified() ?: item.dngDir?.lastModified() ?: 0L
    }
}

@Composable
fun GalleryScreen(context: Context) {
    val galleryItems = remember { mutableStateListOf<GalleryItem>() }
    
    fun refreshItems() {
        val items = loadGalleryItems(context)
        galleryItems.clear()
        galleryItems.addAll(items)
    }

    LaunchedEffect(Unit) {
        refreshItems()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("GALLERY", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        if (galleryItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings found.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(galleryItems, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(Color(0xFF111111))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail Preview section
                        val bitmap = remember(item.previewFile) {
                            item.previewFile?.let { file ->
                                try {
                                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(Color(0xFF222222)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                 Text(
                                     text = if (item.hevcFile != null) "HEVC" else if (item.dngDir != null) "DNG" else "RAW",
                                     color = Color.DarkGray,
                                     fontSize = 12.sp,
                                     fontWeight = FontWeight.Bold
                                 )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Info section
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = item.displayName,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(item.sizeString, color = Color.Gray, fontSize = 12.sp)
                        }
                        
                        val isThisExtracting = VideoRendererService.isRendering && VideoRendererService.renderingPath == item.rawFile?.absolutePath
                        
                        if (isThisExtracting) {
                            // Extraction progress
                            Column(modifier = Modifier.width(120.dp), horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${VideoRendererService.currentPhase} (${(VideoRendererService.progressFraction * 100).toInt()}%)",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = VideoRendererService.progressFraction,
                                    color = Color.White,
                                    trackColor = Color.DarkGray,
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item.rawFile != null) {
                                        // Extract DNG button
                                        Button(
                                            onClick = {
                                                VideoRendererService.startDngExtraction(context, item.rawFile.absolutePath)
                                            },
                                            shape = androidx.compose.ui.graphics.RectangleShape,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("DNG", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        // Export HEVC button
                                        Button(
                                            onClick = {
                                                VideoRendererService.startHevcExport(context, item.rawFile.absolutePath)
                                            },
                                            shape = androidx.compose.ui.graphics.RectangleShape,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("HEVC", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    } else {
                                         Text(
                                             text = if (item.dngDir != null) "DNG EXTRACTED" else "HEVC EXPORTED",
                                             color = Color.Gray,
                                             fontSize = 11.sp,
                                             fontWeight = FontWeight.Bold,
                                             modifier = Modifier.padding(horizontal = 8.dp)
                                         )
                                         Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    
                                    // Delete button
                                     Button(
                                         onClick = {
                                             item.rawFile?.let { if (it.exists()) it.delete() }
                                             item.hevcFile?.let { if (it.exists()) it.delete() }
                                             item.dngDir?.let { dir ->
                                                 if (dir.exists()) {
                                                     dir.deleteRecursively()
                                                 }
                                             }
                                             Toast.makeText(context, "Deleted ${item.displayName}", Toast.LENGTH_SHORT).show()
                                             refreshItems()
                                         },
                                         shape = androidx.compose.ui.graphics.RectangleShape,
                                         colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                                         contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                         modifier = Modifier.height(32.dp)
                                     ) {
                                         Text("DELETE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                     }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Periodically check if extraction finished to refresh list
    if (!VideoRendererService.isRendering) {
        LaunchedEffect(VideoRendererService.isRendering) {
            refreshItems()
        }
    }
}
