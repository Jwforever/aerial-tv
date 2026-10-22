package app.orchard.tv.files

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.theme.canvasDensity
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A file browser designed as part of Aerial, not as a system utility: the wallpaper stays behind
 * it, storage is a card shelf, and folders and files are tiles instead of a list. Files are handed
 * to the TV's own apps; the transfer sheet turns the TV into a small local upload/download page.
 */
class FileManagerActivity : ComponentActivity() {

    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setBackgroundDrawable(null)
        AerialSettings.init(this)
        Sounds.init(this)
        Wallpapers.init(this)
        hasAccess = fileAccessGranted()

        setContent {
            val density = Density(
                density = canvasDensity(resources.displayMetrics),
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(LocalReducedMotion provides false, LocalDensity provides density) {
                FilesScreen(
                    hasAccess = hasAccess,
                    onRequestAccess = ::requestFileAccess,
                    onOpen = ::openFile,
                    onExit = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = fileAccessGranted()
    }

    private fun fileAccessGranted(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 -> ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            if (runCatching { startActivity(appSettings) }.isFailure) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) hasAccess = fileAccessGranted()
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.files_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return "*/*"
        if (file.extension.equals("apk", ignoreCase = true)) return "application/vnd.android.package-archive"
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private companion object {
        const val REQUEST_STORAGE = 41
    }
}

private data class StorageRoot(
    val label: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
)

private enum class ViewMode { Permission, Overview, Directory }

private val storageCardShape = RoundedCornerShape(26.dp)
private val fileCardShape = RoundedCornerShape(20.dp)
private val folderCardColors = listOf(Color(0xFF202A39), Color(0xFF0B0F15))
private val folderMiniColors = listOf(Color(0xFF4BA7FF), Color(0xFF124A91))

@Composable
private fun FilesScreen(
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onOpen: (File) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var roots by remember { mutableStateOf<List<StorageRoot>>(emptyList()) }
    var root by remember { mutableStateOf<StorageRoot?>(null) }
    var current by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var transferOpen by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val grantFocus = remember { FocusRequester() }

    fun goBack() {
        val dir = current
        when {
            !hasAccess || dir == null -> onExit()
            root == null || dir.absolutePath == root?.path -> {
                current = null
                root = null
                entries = emptyList()
                readError = null
            }
            else -> {
                current = dir.parentFile
                entries = emptyList()
                readError = null
            }
        }
    }

    fun openRoot(volume: StorageRoot) {
        root = volume
        current = File(volume.path)
        entries = emptyList()
        readError = null
    }

    fun openDrop() {
        val dir = FileTransferServer.transferDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        if (!dir.isDirectory) {
            readError = context.getString(R.string.files_read_failed)
            return
        }
        root = StorageRoot("AerialDrop", dir.absolutePath, 0L, 0L)
        current = dir
        entries = emptyList()
        readError = null
    }

    LaunchedEffect(hasAccess) {
        if (hasAccess) roots = withContext(Dispatchers.IO) { storageRoots(context) }
    }

    LaunchedEffect(current, hasAccess, reload) {
        val dir = current
        if (!hasAccess || dir == null) return@LaunchedEffect
        loading = true
        readError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { dir.listFiles()?.toList().orEmpty().sortedWith(entriesOrder) }
        }
        loading = false
        entries = result.getOrDefault(emptyList())
        readError = result.exceptionOrNull()?.let { context.getString(R.string.files_read_failed) }
    }

    LaunchedEffect(hasAccess, current, roots, entries, loading, transferOpen) {
        if (!hasAccess) {
            withFrameNanos { }
            runCatching { grantFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (transferOpen || loading) return@LaunchedEffect
        withFrameNanos { }
        val target = when {
            current == null && roots.isNotEmpty() -> first
            current != null && entries.isNotEmpty() -> first
            else -> backFocus
        }
        runCatching { target.requestFocus() }
    }

    BackHandler {
        if (transferOpen) transferOpen = false else goBack()
    }

    val mode = when {
        !hasAccess -> ViewMode.Permission
        current == null -> ViewMode.Overview
        else -> ViewMode.Directory
    }

    val title = when {
        mode == ViewMode.Overview -> stringResource(R.string.files_title)
        root != null && current?.absolutePath == root?.path -> root?.label.orEmpty()
        else -> current?.name ?: stringResource(R.string.files_title)
    }
    val subtitle = when {
        mode == ViewMode.Overview -> stringResource(R.string.files_subtitle)
        mode == ViewMode.Permission -> stringResource(R.string.files_permission_title)
        loading -> current?.absolutePath.orEmpty()
        else -> "${current?.absolutePath.orEmpty()}  ·  ${stringResource(R.string.files_item_count, entries.size)}"
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05070B))) {
        val frost = Wallpapers.frosted
        if (frost != null) {
            Image(
                bitmap = frost,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.62f),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x33000000),
                        0.42f to Color(0xB3000000),
                        1f to Color(0xF2000000),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 26.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BackPill(
                    label = stringResource(R.string.files_back),
                    focusRequester = backFocus,
                    onClick = ::goBack,
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(
                        title,
                        style = Type.LargeTitle.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(subtitle, style = Type.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (mode != ViewMode.Permission) {
                    Spacer(Modifier.width(18.dp))
                    HeaderPill(
                        glyph = Glyph.Globe,
                        label = stringResource(R.string.files_lan),
                        onClick = { transferOpen = true },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            when (mode) {
                ViewMode.Permission -> PermissionPanel(
                    onRequestAccess = onRequestAccess,
                    focusRequester = grantFocus,
                    modifier = Modifier.weight(1f),
                )
                ViewMode.Overview -> StorageOverview(
                    roots = roots,
                    focusRequester = first,
                    onOpenRoot = ::openRoot,
                    onOpenDrop = ::openDrop,
                    onOpenTransfer = { transferOpen = true },
                    modifier = Modifier.weight(1f),
                )
                ViewMode.Directory -> DirectoryGrid(
                    entries = entries,
                    loading = loading,
                    error = readError,
                    context = context,
                    focusRequester = first,
                    onOpen = { file ->
                        if (file.isDirectory) {
                            current = file
                            entries = emptyList()
                            readError = null
                        } else {
                            onOpen(file)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (transferOpen) TransferSheet(onDismiss = {
            transferOpen = false
            reload++
        })
    }
}

@Composable
private fun StorageOverview(
    roots: List<StorageRoot>,
    focusRequester: FocusRequester,
    onOpenRoot: (StorageRoot) -> Unit,
    onOpenDrop: () -> Unit,
    onOpenTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val internal = roots.firstOrNull()
    val extra = roots.drop(1)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (internal != null) {
                VolumeCard(
                    volume = internal,
                    onClick = { onOpenRoot(internal) },
                    focusRequester = focusRequester,
                    modifier = Modifier.weight(1.55f).fillMaxHeight(),
                )
            } else {
                EmptyCard(
                    text = stringResource(R.string.files_loading),
                    modifier = Modifier.weight(1.55f).fillMaxHeight(),
                )
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ActionCard(
                    glyph = Glyph.Folder,
                    title = "AerialDrop",
                    subtitle = stringResource(R.string.files_drop_subtitle),
                    colors = listOf(Color(0xFF38D87A), Color(0xFF096B3A)),
                    onClick = onOpenDrop,
                    modifier = Modifier.weight(1f),
                )
                ActionCard(
                    glyph = Glyph.Globe,
                    title = stringResource(R.string.files_lan_title),
                    subtitle = stringResource(R.string.files_lan_step_scan),
                    colors = listOf(Color(0xFF2D2D31), Color(0xFF161619)),
                    onClick = onOpenTransfer,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().height(94.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                extra.forEach { volume ->
                    VolumeCard(
                        volume = volume,
                        onClick = { onOpenRoot(volume) },
                        focusRequester = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(
    volume: StorageRoot,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = if (volume.path == Environment.getExternalStorageDirectory().absolutePath) {
        listOf(Color(0xFF62C2FF), Color(0xFF0D5DB5), Color(0xFF061E48))
    } else {
        listOf(Color(0xFF8D90FF), Color(0xFF383A91), Color(0xFF161638))
    }
    val fraction = if (volume.totalBytes > 0L) {
        (volume.freeBytes.toFloat() / volume.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }

    Pressable(
        onClick = onClick,
        focusRequester = focusRequester,
        focusedScale = 1.018f,
        modifier = modifier,
    ) { state ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (state.focused) Modifier.shadow(22.dp, storageCardShape) else Modifier)
                .clip(storageCardShape)
                .background(Brush.linearGradient(colors))
                .border(
                    if (state.focused) 2.dp else 1.dp,
                    if (state.focused) Color.White else Color.White.copy(alpha = 0.14f),
                    storageCardShape,
                ),
        ) {
            if (compact) {
                GlyphIcon(
                    Glyph.Folder,
                    Color.White.copy(alpha = 0.10f),
                    78.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 4.dp),
                )
            } else if (volume.totalBytes > 0L) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 30.dp, bottom = 26.dp)
                        .size(138.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    StorageRing(fraction = fraction, modifier = Modifier.fillMaxSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(
                            "${(fraction * 100).roundToInt()}%",
                            style = Type.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                        )
                        BasicText(
                            stringResource(R.string.files_available),
                            style = Type.Caption.copy(color = Color.White.copy(alpha = 0.62f)),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.12f)),
                            start = Offset.Zero,
                            end = Offset(1000f, 700f),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().padding(if (compact) 18.dp else 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Folder, Color.White.copy(alpha = 0.94f), if (compact) 18.dp else 25.dp)
                    Spacer(Modifier.width(10.dp))
                    BasicText(volume.label, style = Type.Title.copy(fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.weight(1f))
                if (!compact) {
                    BasicText(
                        Formatter.formatShortFileSize(LocalContext.current, volume.freeBytes),
                        style = Type.LargeTitle.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
                    )
                    BasicText(
                        stringResource(
                            R.string.files_total,
                            Formatter.formatShortFileSize(LocalContext.current, volume.totalBytes),
                        ),
                        style = Type.Caption.copy(color = Color.White.copy(alpha = 0.70f)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.20f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.82f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    glyph: Glyph,
    title: String,
    subtitle: String,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(onClick = onClick, focusedScale = 1.018f, modifier = modifier) { state ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (state.focused) Modifier.shadow(18.dp, storageCardShape) else Modifier)
                .clip(storageCardShape)
                .background(Brush.linearGradient(colors))
                .border(
                    if (state.focused) 2.dp else 1.dp,
                    if (state.focused) Color.White else Color.White.copy(alpha = 0.13f),
                    storageCardShape,
                ),
        ) {
            GlyphIcon(
                glyph,
                Color.White.copy(alpha = 0.10f),
                110.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.13f), Color.Transparent, Color.Black.copy(alpha = 0.15f)),
                            start = Offset.Zero,
                            end = Offset(700f, 500f),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                GlyphIcon(glyph, Color.White.copy(alpha = 0.94f), 22.dp)
                Spacer(Modifier.weight(1f))
                BasicText(title, style = Type.Title.copy(fontWeight = FontWeight.SemiBold))
                BasicText(
                    subtitle,
                    style = Type.Caption.copy(color = Color.White.copy(alpha = 0.68f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DirectoryGrid(
    entries: List<File>,
    loading: Boolean,
    error: String?,
    context: android.content.Context,
    focusRequester: FocusRequester,
    onOpen: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (loading) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyCard(text = stringResource(R.string.files_loading))
        }
        return
    }
    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyCard(text = error ?: stringResource(R.string.files_empty))
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(entries, key = { _, file -> file.absolutePath }) { index, file ->
            FileCard(
                file = file,
                detail = detailFor(file, context),
                onClick = { onOpen(file) },
                focusRequester = if (index == 0) focusRequester else null,
            )
        }
    }
}

@Composable
private fun FileCard(
    file: File,
    detail: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val thumb = if (file.isImageFile()) rememberThumbnail(file) else null
    val style = styleFor(file)
    val folderPreview = if (file.isDirectory) rememberFolderPreview(file) else emptyList()
    val typeLabel = if (file.extension.isBlank()) "FILE" else file.extension.uppercase(Locale.ROOT).take(4)

    Pressable(
        onClick = onClick,
        focusRequester = focusRequester,
        focusedScale = 1.035f,
        modifier = Modifier.fillMaxWidth(),
    ) { state ->
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .then(if (state.focused) Modifier.shadow(20.dp, fileCardShape) else Modifier)
                    .clip(fileCardShape)
                    .background(Brush.linearGradient(if (file.isDirectory) folderCardColors else style.colors))
                    .border(
                        if (state.focused) 2.dp else 1.dp,
                        if (state.focused) Color.White else Color.White.copy(alpha = 0.11f),
                        fileCardShape,
                    ),
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.32f),
                                ),
                            ),
                    )
                } else if (file.isDirectory) {
                    if (folderPreview.isEmpty()) {
                        GlyphIcon(
                            Glyph.Folder,
                            Color.White.copy(alpha = 0.92f),
                            50.dp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        FolderPreview(folderPreview)
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GlyphIcon(style.glyph, Color.White.copy(alpha = 0.88f), 30.dp)
                    }
                }

                if (!file.isDirectory) {
                    BasicText(
                        typeLabel,
                        style = Type.Caption.copy(
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.30f))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            BasicText(
                file.name,
                style = Type.Body.copy(
                    color = if (state.focused) Color.White else Color.White.copy(alpha = 0.88f),
                    fontWeight = if (state.focused) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!file.isDirectory) {
                BasicText(
                    detail,
                    style = Type.Caption.copy(
                        color = if (state.focused) Color.White.copy(alpha = 0.62f) else Palette.SecondaryLabel,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StorageRing(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = Color.White.copy(alpha = 0.16f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White.copy(alpha = 0.82f),
            startAngle = -90f,
            sweepAngle = fraction.coerceIn(0f, 1f) * 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun EmptyCard(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 520.dp)
            .liquidGlass(28.dp)
            .padding(horizontal = 34.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(Glyph.Folder, Palette.SecondaryLabel, 32.dp)
        Spacer(Modifier.height(12.dp))
        BasicText(text, style = Type.Callout)
    }
}

@Composable
private fun PermissionPanel(
    onRequestAccess: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .liquidGlass(28.dp)
                .padding(horizontal = 30.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphIcon(Glyph.Folder, Palette.SecondaryLabel, 34.dp)
            Spacer(Modifier.height(14.dp))
            BasicText(stringResource(R.string.files_permission_title), style = Type.Title)
            Spacer(Modifier.height(8.dp))
            BasicText(
                stringResource(R.string.files_permission_body),
                style = Type.Callout,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(20.dp))
            BackPill(
                label = stringResource(R.string.files_grant),
                focusRequester = focusRequester,
                onClick = onRequestAccess,
            )
        }
    }
}

@Composable
private fun BackPill(
    label: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) = HeaderPill(
    glyph = Glyph.ChevronLeft,
    label = label,
    focusRequester = focusRequester,
    onClick = onClick,
)

@Composable
private fun HeaderPill(
    glyph: Glyph,
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Pressable(
        onClick = onClick,
        focusRequester = focusRequester,
        focusedScale = 1.04f,
    ) { state ->
        val background by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill,
            tween(140, easing = Motion.EaseOut),
            label = "files-pill-bg",
        )
        val foreground = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .height(40.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(glyph, foreground, 16.dp)
            BasicText(label, style = Type.Body.copy(color = foreground))
        }
    }
}

@Composable
private fun TransferSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val address = FileTransferServer.start(context)
        url = address
        qr = address?.let { PhoneServer.qr(it, 18) }
    }
    DisposableEffect(Unit) {
        onDispose { FileTransferServer.stop() }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .dismissOnTap(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .width(780.dp)
                    .consumeTaps()
                    .liquidGlass(30.dp)
                    .padding(horizontal = 32.dp, vertical = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(stringResource(R.string.files_lan_title), style = Type.LargeTitle)
                    Spacer(Modifier.height(10.dp))
                    BasicText(stringResource(R.string.files_lan_body), style = Type.Callout)
                    Spacer(Modifier.height(20.dp))
                    StepRow(1, stringResource(R.string.files_lan_step_scan))
                    StepRow(2, stringResource(R.string.files_lan_step_upload))
                    StepRow(3, stringResource(R.string.files_lan_step_find))
                    Spacer(Modifier.height(22.dp))
                    HeaderPill(
                        glyph = Glyph.Check,
                        label = stringResource(R.string.files_lan_close),
                        focusRequester = closeFocus,
                        onClick = onDismiss,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(236.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White)
                            .padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = qr
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            BasicText(
                                stringResource(R.string.files_lan_unavailable),
                                style = Type.Caption.copy(color = Color(0xFF3A3A3C)),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    url?.let {
                        BasicText(
                            stringResource(R.string.files_lan_url, it.removePrefix("http://")),
                            style = Type.Caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(236.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Palette.FillStrong),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(number.toString(), style = Type.Caption.copy(color = Palette.Label))
        }
        BasicText(text, style = Type.Body)
    }
}

@Composable
private fun FolderPreview(files: List<File>) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniTile(files.getOrNull(0), Modifier.weight(1f).fillMaxHeight())
            MiniTile(files.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniTile(files.getOrNull(2), Modifier.weight(1f).fillMaxHeight())
            MiniTile(files.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun MiniTile(file: File?, modifier: Modifier = Modifier) {
    val style = file?.let {
        if (it.isDirectory) FileStyle(folderMiniColors, Glyph.Folder) else styleFor(it)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (style != null) Brush.linearGradient(style.colors)
                else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.025f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null && style != null) {
            if (file.isDirectory) {
                GlyphIcon(Glyph.Folder, Color.White.copy(alpha = 0.88f), 17.dp)
            } else {
                BasicText(
                    if (file.extension.isBlank()) "FILE" else file.extension.uppercase(Locale.ROOT).take(4),
                    style = Type.Caption.copy(
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun rememberFolderPreview(dir: File): List<File> {
    var files by remember(dir.absolutePath, dir.lastModified()) { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(dir.absolutePath, dir.lastModified()) {
        files = withContext(Dispatchers.IO) {
            runCatching { dir.listFiles()?.toList().orEmpty().sortedWith(entriesOrder).take(4) }.getOrDefault(emptyList())
        }
    }
    return files
}

@Composable
private fun rememberThumbnail(file: File): ImageBitmap? {
    var image by remember(file.absolutePath, file.lastModified()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.absolutePath, file.lastModified()) {
        image = withContext(Dispatchers.IO) {
            runCatching { decodeThumbnail(file)?.asImageBitmap() }.getOrNull()
        }
    }
    return image
}

private fun decodeThumbnail(file: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 640 && bounds.outHeight / (sample * 2) >= 360) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private val entriesOrder = compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })

private fun File.isImageFile(): Boolean = when (extension.lowercase(Locale.ROOT)) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> true
    else -> false
}

private data class FileStyle(val colors: List<Color>, val glyph: Glyph)

private fun styleFor(file: File): FileStyle {
    if (file.isDirectory) return FileStyle(listOf(Color(0xFF2A2C33), Color(0xFF121317)), Glyph.Folder)
    return when (file.extension.lowercase(Locale.ROOT)) {
        "apk" -> FileStyle(listOf(Color(0xFF5AC8FA), Color(0xFF0A5C9E)), Glyph.Bag)
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" ->
            FileStyle(listOf(Color(0xFF43D17A), Color(0xFF0B5A35)), Glyph.Photo)
        "mp4", "mkv", "avi", "mov", "webm", "ts", "m2ts", "m3u8" ->
            FileStyle(listOf(Color(0xFFFFB340), Color(0xFF9A4B00)), Glyph.Play)
        "mp3", "flac", "wav", "aac", "m4a", "ogg" ->
            FileStyle(listOf(Color(0xFFE75B9A), Color(0xFF7A1747)), Glyph.Speaker)
        "pdf" -> FileStyle(listOf(Color(0xFFFF6B62), Color(0xFF8E1B16)), Glyph.Info)
        "zip", "rar", "7z", "tar", "gz" ->
            FileStyle(listOf(Color(0xFFFFD45C), Color(0xFF8A6300)), Glyph.Folder)
        else -> FileStyle(listOf(Color(0xFF62748E), Color(0xFF263244)), Glyph.Info)
    }
}

private fun detailFor(file: File, context: android.content.Context): String {
    if (file.isDirectory) return context.getString(R.string.files_folder)
    val size = Formatter.formatShortFileSize(context, file.length())
    val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified()))
    val label = if (file.extension.equals("apk", ignoreCase = true)) {
        " · ${context.getString(R.string.files_apk)}"
    } else {
        ""
    }
    return "$size$label · $date"
}

private fun storageRoots(context: android.content.Context): List<StorageRoot> {
    val roots = ArrayList<StorageRoot>()
    val seen = HashSet<String>()
    fun add(label: String, path: String) {
        if (path.isBlank() || !seen.add(path)) return
        val file = File(path)
        if (!file.exists()) return
        roots += rootFor(label, path)
    }

    add(context.getString(R.string.files_internal), Environment.getExternalStorageDirectory().absolutePath)
    context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
        val marker = "/Android/data/"
        val index = dir.absolutePath.indexOf(marker)
        if (index > 0) {
            val path = dir.absolutePath.substring(0, index)
            val label = if (roots.any { it.path == path }) {
                context.getString(R.string.files_external)
            } else {
                "${context.getString(R.string.files_external)} · ${File(path).name}"
            }
            add(label, path)
        }
    }
    return roots
}

private fun rootFor(label: String, path: String): StorageRoot {
    val stat = runCatching { StatFs(path) }.getOrNull()
    return StorageRoot(
        label = label,
        path = path,
        freeBytes = stat?.availableBytes ?: 0L,
        totalBytes = stat?.totalBytes ?: 0L,
    )
}
