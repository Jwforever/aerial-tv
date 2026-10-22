package app.orchard.tv.files

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.graphics.Bitmap
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A small, remote-friendly file browser for the storage volumes Aerial can read. It is opened from
 * the Files tile in the app grid; no file is copied out of the TV, and opening one hands it to the
 * app the TV already associates with its type.
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
        val mime = mimeType(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
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

    LaunchedEffect(hasAccess) {
        if (hasAccess) {
            roots = withContext(Dispatchers.IO) { storageRoots(context) }
        }
    }

    LaunchedEffect(current, hasAccess) {
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

    LaunchedEffect(hasAccess, current, roots, entries, loading) {
        if (!hasAccess) {
            withFrameNanos { }
            runCatching { grantFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (loading) return@LaunchedEffect
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

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF0A1018),
                    0.55f to Color(0xFF05080C),
                    1f to Color.Black,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 28.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(
                    label = stringResource(R.string.files_back),
                    focusRequester = backFocus,
                    onClick = ::goBack,
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(
                        text = when {
                            mode == ViewMode.Overview -> stringResource(R.string.files_title)
                            root != null && current?.absolutePath == root?.path -> root?.label.orEmpty()
                            else -> current?.name ?: stringResource(R.string.files_title)
                        },
                        style = Type.LargeTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        text = when {
                            mode == ViewMode.Overview -> stringResource(R.string.files_subtitle)
                            current != null -> current?.absolutePath.orEmpty()
                            else -> stringResource(R.string.files_permission_title)
                        },
                        style = Type.Callout,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

            Spacer(Modifier.height(18.dp))

            when (mode) {
                ViewMode.Permission -> PermissionPanel(
                    onRequestAccess = onRequestAccess,
                    focusRequester = grantFocus,
                    modifier = Modifier.weight(1f),
                )
                ViewMode.Overview -> StoragePanel(
                    roots = roots,
                    focusRequester = first,
                    onOpen = { selected ->
                        root = selected
                        current = File(selected.path)
                        entries = emptyList()
                    },
                    modifier = Modifier.weight(1f),
                )
                ViewMode.Directory -> DirectoryPanel(
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

        if (transferOpen) TransferSheet(onDismiss = { transferOpen = false })
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
        // Binding the server and drawing the QR are both short; doing them here means the panel
        // cannot be closed between the two and leave a server running with no sheet.
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
private fun StoragePanel(
    roots: List<StorageRoot>,
    focusRequester: FocusRequester,
    onOpen: (StorageRoot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        BasicText(
            stringResource(R.string.files_root),
            style = Type.Headline,
            modifier = Modifier.padding(start = 14.dp, bottom = 10.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(26.dp)
                .padding(8.dp),
        ) {
            if (roots.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    BasicText(stringResource(R.string.files_loading), style = Type.Callout)
                }
            } else {
                roots.forEachIndexed { index, volume ->
                    FileRow(
                        glyph = Glyph.Folder,
                        title = volume.label,
                        detail = stringResource(
                            R.string.files_free_of,
                            Formatter.formatShortFileSize(LocalContext.current, volume.freeBytes),
                            Formatter.formatShortFileSize(LocalContext.current, volume.totalBytes),
                        ),
                        onClick = { onOpen(volume) },
                        focusRequester = if (index == 0) focusRequester else null,
                        trailing = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryPanel(
    entries: List<File>,
    loading: Boolean,
    error: String?,
    context: android.content.Context,
    focusRequester: FocusRequester,
    onOpen: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(26.dp)
                .padding(8.dp),
        ) {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    BasicText(stringResource(R.string.files_loading), style = Type.Callout)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        error ?: stringResource(R.string.files_empty),
                        style = Type.Callout.copy(color = if (error != null) Palette.Orange else Palette.SecondaryLabel),
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(entries, key = { _, file -> file.absolutePath }) { index, file ->
                        FileRow(
                            glyph = glyphFor(file),
                            title = file.name,
                            detail = detailFor(file, context),
                            onClick = { onOpen(file) },
                            focusRequester = if (index == 0) focusRequester else null,
                            trailing = file.isDirectory,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    glyph: Glyph,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    trailing: Boolean,
) {
    Pressable(
        onClick = onClick,
        focusRequester = focusRequester,
        focusedScale = 1.015f,
        modifier = Modifier.fillMaxWidth(),
    ) { state ->
        val background by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(140, easing = Motion.EaseOut),
            label = "files-row-bg",
        )
        val foreground = if (state.focused) Palette.OnFocus else Palette.Label
        val secondary = if (state.focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.SecondaryLabel
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .height(52.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(glyph, foreground, 20.dp)
            Column(Modifier.weight(1f)) {
                BasicText(
                    title,
                    style = Type.Body.copy(color = foreground),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    BasicText(
                        detail,
                        style = Type.Caption.copy(color = secondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing) GlyphIcon(Glyph.ChevronRight, secondary, 15.dp)
        }
    }
}

private val entriesOrder = compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })

private fun glyphFor(file: File): Glyph {
    if (file.isDirectory) return Glyph.Folder
    return when (file.extension.lowercase(Locale.ROOT)) {
        "apk" -> Glyph.Bag
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> Glyph.Photo
        "mp4", "mkv", "avi", "mov", "webm", "ts", "m2ts", "m3u8" -> Glyph.Play
        else -> Glyph.Info
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
        val stat = runCatching { StatFs(path) }.getOrNull()
        roots += StorageRoot(
            label = label,
            path = path,
            freeBytes = stat?.availableBytes ?: 0L,
            totalBytes = stat?.totalBytes ?: 0L,
        )
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
