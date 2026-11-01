@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.achut.moodboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ---------- Colors ----------
val Red = Color(0xFFE60023)
val RedDark = Color(0xFFAD081B)
val RedSoft = Color(0xFFFFE3E5)
val Cream = Color(0xFFFFF8F7)

// ---------- Data ----------
data class Pin(val id: String, val imagePath: String, val caption: String, val ratio: Float)
data class Space(val id: String, val name: String, val pins: List<Pin>)

object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "moodboard.json")

    fun load(ctx: Context): List<Space> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                val pa = s.getJSONArray("pins")
                Space(
                    s.getString("id"), s.getString("name"),
                    List(pa.length()) { j ->
                        val p = pa.getJSONObject(j)
                        Pin(
                            p.getString("id"), p.getString("image"),
                            p.optString("caption"), p.optDouble("ratio", 1.0).toFloat()
                        )
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, spaces: List<Space>) {
        val arr = JSONArray()
        spaces.forEach { s ->
            val pa = JSONArray()
            s.pins.forEach { p ->
                pa.put(
                    JSONObject().put("id", p.id).put("image", p.imagePath)
                        .put("caption", p.caption).put("ratio", p.ratio.toDouble())
                )
            }
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("pins", pa))
        }
        val tmp = File(ctx.filesDir, "moodboard.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    // Copies a picked image into the app's own storage (resized, max 1280px).
    // Returns the saved file path and the image's width/height ratio.
    fun importImage(ctx: Context, uri: Uri): Pair<String, Float>? {
        return try {
            val bmp = decodeScaled(ctx, uri, 1280) ?: return null
            val dir = File(ctx.filesDir, "images").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString() + ".jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val ratio = bmp.width.toFloat() / bmp.height
            bmp.recycle()
            out.absolutePath to ratio
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Red, onPrimary = Color.White,
                    primaryContainer = RedSoft, onPrimaryContainer = RedDark,
                    secondary = RedDark, background = Cream, surface = Cream
                )
            ) { MoodBoardApp() }
        }
    }
}

@Composable
fun MoodBoardApp() {
    val ctx = LocalContext.current
    var spaces by remember { mutableStateOf(Store.load(ctx)) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    fun update(list: List<Space>) {
        spaces = list
        Store.save(ctx, list)
    }

    fun updatePins(spaceId: String, change: (List<Pin>) -> List<Pin>) =
        update(spaces.map { if (it.id == spaceId) it.copy(pins = change(it.pins)) else it })

    val open = spaces.find { it.id == openId }
    if (open == null) {
        HomeScreen(
            spaces = spaces,
            onOpen = { openId = it.id },
            onCreate = { name -> update(spaces + Space(UUID.randomUUID().toString(), name, emptyList())) },
            onRename = { s, name -> update(spaces.map { if (it.id == s.id) it.copy(name = name) else it }) },
            onDelete = { s ->
                s.pins.forEach { File(it.imagePath).delete() }
                update(spaces.filter { it.id != s.id })
            }
        )
    } else {
        BackHandler { openId = null }
        SpaceScreen(
            space = open,
            onBack = { openId = null },
            onAddPin = { pin -> updatePins(open.id) { listOf(pin) + it } },
            onEditPin = { pin, text ->
                updatePins(open.id) { list -> list.map { if (it.id == pin.id) it.copy(caption = text) else it } }
            },
            onDeletePin = { pin ->
                File(pin.imagePath).delete()
                updatePins(open.id) { list -> list.filter { it.id != pin.id } }
            }
        )
    }
}

// ---------- Home (list of spaces) ----------
@Composable
fun HomeScreen(
    spaces: List<Space>,
    onOpen: (Space) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Space, String) -> Unit,
    onDelete: (Space) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<Space?>(null) }
    var renameFor by remember { mutableStateOf<Space?>(null) }
    var deleteFor by remember { mutableStateOf<Space?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Dashboard, null, tint = Red)
                        Spacer(Modifier.width(8.dp))
                        Text("Moodboard", fontWeight = FontWeight.Bold, color = Red)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                shape = CircleShape, containerColor = Red, contentColor = Color.White
            ) { Icon(Icons.Filled.CreateNewFolder, "New space") }
        }
    ) { pad ->
        if (spaces.isEmpty()) {
            EmptyState(
                Icons.Filled.Collections, "No spaces yet",
                "Tap the red button to create your first space", Modifier.padding(pad)
            )
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp
            ) {
                items(spaces, key = { it.id }) { s ->
                    SpaceCard(s, onClick = { onOpen(s) }, onLongClick = { actionFor = s })
                }
            }
        }
    }

    if (showCreate) TextInputDialog(
        title = "New space", initial = "", label = "Space name", confirm = "Create",
        requireText = true,
        onConfirm = { onCreate(it); showCreate = false },
        onDismiss = { showCreate = false }
    )
    actionFor?.let { s ->
        ActionsDialog(
            title = s.name, editLabel = "Rename",
            onEdit = { renameFor = s; actionFor = null },
            onDelete = { deleteFor = s; actionFor = null },
            onDismiss = { actionFor = null }
        )
    }
    renameFor?.let { s ->
        TextInputDialog(
            title = "Rename space", initial = s.name, label = "Space name", confirm = "Save",
            requireText = true,
            onConfirm = { onRename(s, it); renameFor = null },
            onDismiss = { renameFor = null }
        )
    }
    deleteFor?.let { s ->
        ConfirmDialog(
            title = "Delete \"${s.name}\"?",
            message = "This removes the space and its ${s.pins.size} pins from this phone.",
            onConfirm = { onDelete(s); deleteFor = null },
            onDismiss = { deleteFor = null }
        )
    }
}

@Composable
fun SpaceCard(space: Space, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val covers = space.pins.take(3)
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)).background(RedSoft),
            contentAlignment = Alignment.Center
        ) {
            if (covers.isEmpty()) {
                Icon(Icons.Filled.PhotoLibrary, null, tint = Red.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
            } else {
                Collage(covers)
            }
        }
        Text(
            space.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )
        Text(
            "${space.pins.size} pins", fontSize = 12.sp, color = Color.Gray,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
    }
}

@Composable
fun Collage(pins: List<Pin>) {
    if (pins.size == 1) {
        LocalImage(pins[0].imagePath, Modifier.fillMaxSize(), sample = 4)
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LocalImage(pins[0].imagePath, Modifier.weight(2f).fillMaxHeight(), sample = 4)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                pins.drop(1).forEach {
                    LocalImage(it.imagePath, Modifier.weight(1f).fillMaxWidth(), sample = 4)
                }
            }
        }
    }
}

// ---------- Inside a space (pins grid) ----------
@Composable
fun SpaceScreen(
    space: Space,
    onBack: () -> Unit,
    onAddPin: (Pin) -> Unit,
    onEditPin: (Pin, String) -> Unit,
    onDeletePin: (Pin) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var importing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pin?>(null) }
    var actionFor by remember { mutableStateOf<Pin?>(null) }
    var editFor by remember { mutableStateOf<Pin?>(null) }
    var deleteFor by remember { mutableStateOf<Pin?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { Store.importImage(ctx, uri) }
                importing = false
                if (result != null) pending = result
                else Toast.makeText(ctx, "Couldn't load that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(space.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${space.pins.size} pins", fontSize = 12.sp, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Red)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                shape = RoundedCornerShape(16.dp), containerColor = Red, contentColor = Color.White
            ) { Icon(Icons.Filled.AddPhotoAlternate, "Add image") }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (space.pins.isEmpty()) {
                EmptyState(
                    Icons.Filled.AddPhotoAlternate, "This space is empty",
                    "Tap the square button to add your first image", Modifier
                )
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 12.dp
                ) {
                    items(space.pins, key = { it.id }) { p ->
                        PinCard(p, onClick = { viewing = p }, onLongClick = { actionFor = p })
                    }
                }
            }
            if (importing) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Red) }
            }
        }
    }

    pending?.let { (path, ratio) ->
        NewPinDialog(
            imagePath = path,
            onSave = { cap ->
                onAddPin(Pin(UUID.randomUUID().toString(), path, cap, ratio))
                pending = null
            },
            onCancel = { File(path).delete(); pending = null }
        )
    }
    viewing?.let { PinViewer(it) { viewing = null } }
    actionFor?.let { p ->
        ActionsDialog(
            title = "Pin", editLabel = "Edit note",
            onEdit = { editFor = p; actionFor = null },
            onDelete = { deleteFor = p; actionFor = null },
            onDismiss = { actionFor = null }
        )
    }
    editFor?.let { p ->
        TextInputDialog(
            title = "Edit note", initial = p.caption, label = "Note", confirm = "Save",
            requireText = false, singleLine = false,
            onConfirm = { onEditPin(p, it); editFor = null },
            onDismiss = { editFor = null }
        )
    }
    deleteFor?.let { p ->
        ConfirmDialog(
            title = "Delete this pin?",
            message = "The image will be removed from this space.",
            onConfirm = { onDeletePin(p); deleteFor = null },
            onDismiss = { deleteFor = null }
        )
    }
}

@Composable
fun PinCard(pin: Pin, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // Follows the image shape, but limited so very tall/wide images don't take over the screen
        LocalImage(
            pin.imagePath,
            Modifier.fillMaxWidth()
                .aspectRatio(pin.ratio.coerceIn(0.7f, 1.5f))
                .clip(RoundedCornerShape(16.dp))
        )
        if (pin.caption.isNotBlank()) {
            Text(
                pin.caption, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun PinViewer(pin: Pin, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LocalImage(
                pin.imagePath, Modifier.fillMaxWidth().weight(1f),
                sample = 1, contentScale = ContentScale.Fit, placeholder = Color.Transparent
            )
            if (pin.caption.isNotBlank()) {
                Text(
                    pin.caption, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                )
            }
        }
    }
}

// ---------- Shared pieces ----------
@Composable
fun LocalImage(
    path: String,
    modifier: Modifier = Modifier,
    sample: Int = 2,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = RedSoft
) {
    val bmp by produceState<ImageBitmap?>(null, path, sample) {
        value = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(modifier.background(placeholder), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(RedSoft),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Red, modifier = Modifier.size(48.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(subtitle, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun NewPinDialog(imagePath: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add a note") },
        text = {
            Column {
                LocalImage(imagePath, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Write something…") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirm: String,
    requireText: Boolean,
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(label) }, singleLine = singleLine,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }, enabled = !requireText || text.isNotBlank()) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ActionsDialog(title: String, editLabel: String, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                ActionRow(Icons.Filled.Edit, editLabel, MaterialTheme.colorScheme.onSurface, onEdit)
                ActionRow(Icons.Filled.Delete, "Delete", Red, onDelete)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun ActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                Text("Delete")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
