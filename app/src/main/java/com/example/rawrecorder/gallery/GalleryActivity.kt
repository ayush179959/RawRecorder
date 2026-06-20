package com.example.rawrecorder.gallery

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.rawrecorder.ui.theme.RawRecorderTheme
import java.io.File

class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RawRecorderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    GalleryScreen(this@GalleryActivity)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val rawRecorderDir = File(docDir, "RawRecorder")
    val rawRecorderDirs = mutableListOf(rawRecorderDir)
    context.getExternalFilesDir(null)?.let {
        rawRecorderDirs.add(File(it, "RawRecorder"))
    }

    for (rDir in rawRecorderDirs) {
        if (!rDir.exists()) continue

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

        val mp4Files = rDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: continue
        for (mp4File in mp4Files) {
            val id = mp4File.nameWithoutExtension
            val builder = itemsMap.getOrPut(id) { GalleryItemBuilder(id) }
            builder.hevcFile = mp4File
        }
    }

    return itemsMap.values.map { builder ->
        val rawFile = builder.rawFile
        val dngDir = builder.dngDir
        val hevcFile = builder.hevcFile
        val previewFile = builder.previewFile

        val isExtracted = dngDir != null || hevcFile != null
        val sizeString = if (rawFile != null) {
            "${rawFile.length() / (1024 * 1024)} MB"
        } else if (dngDir != null && hevcFile != null) {
            val dngCount = dngDir.listFiles { _, name -> name.endsWith(".dng") }?.size ?: 0
            "$dngCount frames, ${hevcFile.length() / (1024 * 1024)} MB video"
        } else if (dngDir != null) {
            val dngCount = dngDir.listFiles { _, name -> name.endsWith(".dng") }?.size ?: 0
            "$dngCount frames"
        } else if (hevcFile != null) {
            "${hevcFile.length() / (1024 * 1024)} MB (Video)"
        } else {
            ""
        }

        GalleryItem(
            id = builder.id,
            displayName = builder.id,
            rawFile = rawFile,
            dngDir = dngDir,
            hevcFile = hevcFile,
            previewFile = previewFile,
            sizeString = sizeString,
            isExtracted = isExtracted
        )
    }.sortedByDescending { item ->
        item.rawFile?.lastModified() ?: item.dngDir?.lastModified() ?: item.hevcFile?.lastModified() ?: 0L
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var name = "unknown"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

private fun playVideo(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun GalleryScreen(activity: GalleryActivity) {
    val context = activity as Context
    val galleryItems = remember { mutableStateListOf<GalleryItem>() }
    var deleteTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var exportTarget by remember { mutableStateOf<GalleryItem?>(null) }

    var selectedLutUri by remember { mutableStateOf<String?>(null) }
    var selectedLutName by remember { mutableStateOf<String?>(null) }

    val lutPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedLutUri = it.toString()
            selectedLutName = getFileName(context, it)
        }
    }

    fun refreshItems() {
        val items = loadGalleryItems(context)
        galleryItems.clear()
        galleryItems.addAll(items)
    }

    LaunchedEffect(Unit) {
        refreshItems()
    }

    if (!VideoRendererService.isRendering) {
        LaunchedEffect(VideoRendererService.isRendering) {
            refreshItems()
        }
    }

    deleteTarget?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onDismiss = { deleteTarget = null },
            onDelete = { raw, dng, hevc ->
                if (raw) item.rawFile?.let { f -> if (f.exists()) f.delete() }
                if (dng) item.dngDir?.let { d -> if (d.exists()) d.deleteRecursively() }
                if (hevc) item.hevcFile?.let { f -> if (f.exists()) f.delete() }
                Toast.makeText(context, "Deleted ${item.displayName}", Toast.LENGTH_SHORT).show()
                deleteTarget = null
                refreshItems()
            }
        )
    }

    exportTarget?.let { item ->
        ExportOptionsDialog(
            lutName = selectedLutName,
            onSelectLut = {
                lutPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            },
            onClearLut = {
                selectedLutUri = null
                selectedLutName = null
            },
            onDismiss = {
                exportTarget = null
            },
            onExport = { config ->
                item.rawFile?.let { f ->
                    VideoRendererService.startHevcExport(context, f.absolutePath, config)
                }
                exportTarget = null
            }
        )
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
                    GalleryCard(
                        item = item,
                        onPlay = { item.hevcFile?.let { playVideo(context, it) } },
                        onDngExport = {
                            item.rawFile?.let { f ->
                                VideoRendererService.startDngExtraction(context, f.absolutePath)
                            }
                        },
                        onHevcExport = { exportTarget = item },
                        onDelete = { deleteTarget = item }
                    )
                }
            }
        }
    }
}

@Composable
fun GalleryCard(
    item: GalleryItem,
    onPlay: () -> Unit,
    onDngExport: () -> Unit,
    onHevcExport: () -> Unit,
    onDelete: () -> Unit
) {
    val isThisExtracting = VideoRendererService.isRendering &&
            (VideoRendererService.renderingPath == item.rawFile?.absolutePath)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThumbnailBox(item = item, modifier = Modifier.size(72.dp))

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.sizeString,
                    color = Color.Gray,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isThisExtracting) {
                    Column {
                        Text(
                            text = "${VideoRendererService.currentPhase} (${(VideoRendererService.progressFraction * 100).toInt()}%)",
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { VideoRendererService.progressFraction },
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0xFF333333),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.rawFile != null) {
                            SmallButton("DNG", Color(0xFF333333), Color.White, onClick = onDngExport)
                            Spacer(modifier = Modifier.width(6.dp))
                            SmallButton("HEVC", Color(0xFF4CAF50), Color.White, onClick = onHevcExport)
                        }
                        if (item.hevcFile != null) {
                            if (item.rawFile != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            SmallButton("PLAY", Color(0xFF1976D2), Color.White, onClick = onPlay)
                        }
                        if (item.rawFile == null && item.hevcFile == null && item.dngDir != null) {
                            Text(
                                text = "DNG ONLY",
                                color = Color(0xFF666666),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            SmallButton("DEL", Color(0xFF424242), Color(0xFFBBBBBB), onClick = onDelete)
        }
    }
}

@Composable
fun ThumbnailBox(item: GalleryItem, modifier: Modifier = Modifier) {
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
        modifier = modifier
            .background(Color(0xFF222222), RoundedCornerShape(6.dp)),
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
                text = when {
                    item.hevcFile != null && item.dngDir != null -> "DNG+V"
                    item.hevcFile != null -> "VIDEO"
                    item.dngDir != null -> "DNG"
                    else -> "RAW"
                },
                color = Color(0xFF555555),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SmallButton(text: String, bgColor: Color, contentColor: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(26.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

@Composable
fun DeleteConfirmDialog(
    item: GalleryItem,
    onDismiss: () -> Unit,
    onDelete: (raw: Boolean, dng: Boolean, hevc: Boolean) -> Unit
) {
    val hasRaw = item.rawFile != null
    val hasDng = item.dngDir != null
    val hasHevc = item.hevcFile != null

    var deleteRaw by remember { mutableStateOf(hasRaw) }
    var deleteDng by remember { mutableStateOf(hasDng) }
    var deleteHevc by remember { mutableStateOf(hasHevc) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCCCCCC),
        title = { Text("Delete \"${item.displayName}\"?", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Select what to delete:", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))

                if (hasRaw) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteRaw,
                            onCheckedChange = { deleteRaw = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF4CAF50),
                                uncheckedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text("Original file", color = Color.White, fontSize = 13.sp)
                            Text(".ayushraw (${item.rawFile!!.length() / (1024 * 1024)} MB)", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }

                if (hasDng) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteDng,
                            onCheckedChange = { deleteDng = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF4CAF50),
                                uncheckedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text("DNG folder", color = Color.White, fontSize = 13.sp)
                            val dngCount = item.dngDir!!.listFiles { _, n -> n.endsWith(".dng") }?.size ?: 0
                            Text("$dngCount frames", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }

                if (hasHevc) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteHevc,
                            onCheckedChange = { deleteHevc = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF4CAF50),
                                uncheckedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text("HEVC export", color = Color.White, fontSize = 13.sp)
                            Text(".mp4 (${item.hevcFile!!.length() / (1024 * 1024)} MB)", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val anySelected = deleteRaw || deleteDng || deleteHevc
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = anySelected,
                        onCheckedChange = { checked ->
                            deleteRaw = checked && hasRaw
                            deleteDng = checked && hasDng
                            deleteHevc = checked && hasHevc
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFFFF9800),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Select all", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            val anySelected = deleteRaw || deleteDng || deleteHevc
            Button(
                onClick = { onDelete(deleteRaw, deleteDng, deleteHevc) },
                enabled = anySelected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (anySelected) Color(0xFFD32F2F) else Color(0xFF444444),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF333333),
                    disabledContentColor = Color(0xFF666666)
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
fun ExportOptionsDialog(
    lutName: String?,
    onSelectLut: () -> Unit,
    onClearLut: () -> Unit,
    onDismiss: () -> Unit,
    onExport: (ExportConfig) -> Unit
) {
    var selectedGamut by remember { mutableStateOf(OutputGamut.REC709) }
    var applyLut by remember { mutableStateOf(false) }
    var bitrateText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCCCCCC),
        title = { Text("HEVC Export Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Target Bitrate", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = bitrateText,
                    onValueChange = { bitrateText = it.filter { c -> c.isDigit() } },
                    placeholder = { Text("Auto (40-120 Mbps)", color = Color.Gray, fontSize = 12.sp) },
                    label = { Text("Bitrate (Mbps)", color = Color.Gray, fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4CAF50),
                        unfocusedBorderColor = Color(0xFF444444),
                        cursorColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(modifier = Modifier.height(12.dp))

                Text("Output Gamut", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = selectedGamut == OutputGamut.REC709,
                        onClick = { selectedGamut = OutputGamut.REC709 },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF4CAF50),
                            unselectedColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text("Rec.709", color = Color.White, fontSize = 13.sp)
                        Text("Standard gamma-encoded output", color = Color.Gray, fontSize = 10.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = selectedGamut == OutputGamut.CINEON_LOG,
                        onClick = { selectedGamut = OutputGamut.CINEON_LOG },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF4CAF50),
                            unselectedColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text("Cineon Log", color = Color.White, fontSize = 13.sp)
                        Text("Log-encoded for color grading", color = Color.Gray, fontSize = 10.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = selectedGamut == OutputGamut.ACES_AP1,
                        onClick = { selectedGamut = OutputGamut.ACES_AP1 },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF4CAF50),
                            unselectedColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text("ACEScct (AP1)", color = Color.White, fontSize = 13.sp)
                        Text("ACES logarithmic color space for grading", color = Color.Gray, fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(modifier = Modifier.height(12.dp))

                Text("LUT (Look-Up Table)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = applyLut,
                        onCheckedChange = { applyLut = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF4CAF50),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Apply LUT after color conversion", color = Color.White, fontSize = 13.sp)
                }

                if (applyLut) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Button(
                            onClick = onSelectLut,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF333333),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (lutName != null) "Selected: $lutName" else "Select .cube LUT file",
                                fontSize = 12.sp
                            )
                        }
                        if (lutName != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onClearLut) {
                                Text("Clear", color = Color(0xFF999999), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bitrate = bitrateText.toIntOrNull()
                    val targetBitrate = if (bitrate != null && bitrate > 0) bitrate * 1_000_000 else null
                    val config = ExportConfig(
                        outputGamut = selectedGamut,
                        targetBitrate = targetBitrate,
                        lutFilePath = if (applyLut) lutName else null
                    )
                    onExport(config)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    contentColor = Color.White
                )
            ) {
                Text("Export", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}
