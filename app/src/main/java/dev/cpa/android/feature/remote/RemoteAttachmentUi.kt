package dev.cpa.android.feature.remote

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.attachments.*
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.data.repository.RemoteSentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/** System pickers feeding a composer's attachments, read in pick order by [RemoteAttachments]. */
class AttachmentPickers(val camera: () -> Unit, val photos: () -> Unit, val files: () -> Unit, val enabled: Boolean)

@Composable
internal fun rememberAttachmentPickers(controller: RemoteAttachments, enabled: Boolean): AttachmentPickers {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reader = remember(context) { AndroidAttachmentReader(context) }
    val scope = rememberCoroutineScope()
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.enqueue(uris.map { uri -> suspend { reader.read(uri, photo = false) } })
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        controller.enqueue(uris.map { uri -> suspend { reader.read(uri, photo = true) } })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = cameraPath
        cameraPath = null
        if (path != null) {
            val accepted = success && controller.enqueue(listOf(suspend {
                try { reader.read(Uri.fromFile(File(path)), photo = true) }
                finally { withContext(Dispatchers.IO) { File(path).delete() } }
            }))
            if (!accepted) scope.launch(Dispatchers.IO) { File(path).delete() }
        }
    }
    val canPick = enabled && !state.locked
    return AttachmentPickers(
        camera = {
            if (canPick && cameraPath == null) scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val folder = File(context.cacheDir, "composer-camera").apply { mkdirs() }
                        File.createTempFile("camera-", ".jpg", folder)
                    }
                    cameraPath = file.path
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.attachments", file))
                } catch (_: Exception) {
                    cameraPath?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }; cameraPath = null
                    controller.error(R.string.camera_unavailable)
                }
            }
        },
        photos = { if (canPick) runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { controller.error(R.string.photo_picker_unavailable) } },
        files = { if (canPick) runCatching { files.launch(arrayOf("*/*")) }.onFailure { controller.error(R.string.file_picker_unavailable) } },
        enabled = canPick,
    )
}

/** Inline picker row plus previews; used where no "+" menu hosts the pickers (and by device tests). */
@Composable
internal fun RemoteAttachmentComposer(controller: RemoteAttachments, enabled: Boolean) {
    val pickers = rememberAttachmentPickers(controller, enabled)
    val c = Cpa.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.testTag("attachment-pickers"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.photos to pickers.photos, R.string.files to pickers.files, R.string.camera to pickers.camera).forEach { (title, action) ->
                Box(Modifier.height(36.dp).background(c.fill, CircleShape).clip(CircleShape).pressable(pickers.enabled, onClick = action)
                    .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(title), style = cpaText(15), color = if (pickers.enabled) c.label else c.tertiaryLabel)
                }
            }
        }
        ComposerAttachmentStrip(controller, enabled)
    }
}

/** Picked images (thumbnails) and files (chips) above the prompt, each with a remove button. */
@Composable
internal fun ComposerAttachmentStrip(controller: RemoteAttachments, enabled: Boolean) {
    val state by controller.state.collectAsStateWithLifecycle()
    val c = Cpa.colors
    var preview by remember { mutableStateOf<String?>(null) }
    val canEdit = enabled && !state.locked
    val images = state.items.filter { it.image }
    val files = state.items.filterNot { it.image }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty() || state.processing > 0) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEach { item -> key(item.id) {
                val bitmap by produceState<ImageBitmap?>(null, item.thumbnail) { value = loadImage(item.thumbnail ?: item.path, null, 360) }
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(22.dp)).background(c.fill).pressable(pressedAlpha = .8f) { preview = item.path }) {
                    bitmap?.let { Image(it, stringResource(R.string.attachment_preview_name, item.name), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.Black.copy(alpha = .6f), CircleShape)
                        .clip(CircleShape).pressable(canEdit, label = stringResource(R.string.ui_attachments_remove_image)) { controller.remove(item.id) }
                        .semantics { contentDescription = "remove:${item.name}" }, contentAlignment = Alignment.Center) {
                        CpaIcon(CpaIcons.Close, null, size = 11.dp, tint = Color.White)
                    }
                }
            } }
            if (state.processing > 0) Box(Modifier.size(120.dp).clip(RoundedCornerShape(22.dp)).background(c.fill), contentAlignment = Alignment.Center) {
                Spinner(size = 22.dp)
            }
        }
        val chips: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { files.forEach { item -> key(item.id) {
                FileAttachmentChip(item.name, canEdit) { controller.remove(item.id) }
            } } }
        }
        if (files.size > 3) Box(Modifier.height(164.dp).verticalScroll(rememberScrollState())) { chips() } else if (files.isNotEmpty()) chips()
    }
    preview?.let { path -> ImagePreview(path, null) { preview = null } }
    state.error?.let { message ->
        CpaAlert(stringResource(R.string.ui_attachments_add_error_title), stringResource(message),
            listOf(DialogAction(stringResource(R.string.ui_common_ok), cancel = true) { controller.clearError() }), onDismiss = controller::clearError)
    }
}

@Composable
private fun FileAttachmentChip(name: String, enabled: Boolean, onRemove: () -> Unit) {
    val c = Cpa.colors
    val (icon, color) = fileTypeStyle(name)
    Row(Modifier.height(40.dp).background(c.fill, RoundedCornerShape(16.dp)).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(color, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) { CpaIcon(icon, null, size = 15.dp, tint = Color.White) }
        Spacer(Modifier.width(8.dp))
        Text(name, Modifier.weight(1f, fill = false), style = cpaText(15), color = c.label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Box(Modifier.size(28.dp, 40.dp).pressable(enabled, label = stringResource(R.string.ui_attachments_remove_named, name), onClick = onRemove)
            .semantics { contentDescription = "remove:$name" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(18.dp).background(c.gray2, CircleShape), contentAlignment = Alignment.Center) { CpaIcon(CpaIcons.Close, null, size = 10.dp, tint = Color.White) }
        }
    }
}

@Composable
private fun fileTypeStyle(name: String): Pair<ImageVector, Color> {
    val c = Cpa.colors
    return when (name.substringAfterLast('.', "").lowercase()) {
        "xls", "xlsx", "numbers", "csv", "tsv" -> CpaIcons.Table to c.green
        "ppt", "pptx", "key" -> CpaIcons.Presentation to c.orange
        "pdf" -> CpaIcons.DocText to c.red
        "doc", "docx", "pages", "rtf", "odt" -> CpaIcons.DocText to c.blue
        "png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "svg" -> CpaIcons.Photo to c.purple
        "mp4", "mov", "avi", "mkv" -> CpaIcons.Film to c.pink
        "mp3", "wav", "m4a", "aac", "flac" -> CpaIcons.Waveform to c.pink
        "zip", "gz", "tar", "rar", "7z", "tgz" -> CpaIcons.Archive to c.brown
        "kt", "kts", "java", "swift", "ts", "tsx", "js", "jsx", "py", "go", "rs", "c", "cpp", "h", "json", "xml", "yml", "yaml", "sh", "html", "css" -> CpaIcons.Code to c.indigo
        else -> CpaIcons.Note to c.gray
    }
}

/** Thumbnails of a prompt's images: one image as a large tile, several in rows of two. */
@Composable
internal fun RemoteSentImages(images: List<RemoteSentImage>, pending: Boolean = false) {
    var preview by remember { mutableStateOf<Int?>(null) }
    val side = if (images.size == 1) 220.dp else 110.dp
    Column(Modifier.alpha(if (pending) .8f else 1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.indices.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { index ->
                    val image = images[index]
                    val bitmap by produceState<ImageBitmap?>(null, image) { value = loadImage(null, image.data, 600) }
                    val label = if (images.size == 1) stringResource(R.string.ui_entry_image) else stringResource(R.string.ui_entry_image_numbered, index + 1)
                    Box(Modifier.size(side).clip(RoundedCornerShape(18.dp)).background(Cpa.colors.fill).pressable(pressedAlpha = .8f, label = label) { preview = index }
                        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        bitmap?.let { Image(it, stringResource(R.string.sent_image_index, index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: CpaIcon(CpaIcons.Photo, null, size = 22.dp, tint = Cpa.colors.secondaryLabel)
                    }
                }
            }
        }
    }
    preview?.takeIf { it in images.indices }?.let { index ->
        ImagePreview(null, images[index].data, onPrevious = if (index > 0) ({ preview = index - 1 }) else null,
            onNext = if (index < images.lastIndex) ({ preview = index + 1 }) else null) { preview = null }
    }
}

private suspend fun loadImage(path: String?, data: String?, edge: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val source = if (path != null) ImageDecoder.createSource(File(path)) else {
            require(data != null && data.length <= (MAX_ATTACHMENT_BYTES * 4 / 3 + 4))
            ImageDecoder.createSource(ByteBuffer.wrap(Base64.getDecoder().decode(data)))
        }
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, edge.toDouble() / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }.asImageBitmap()
    }.getOrNull()
}

/** Full-screen image viewer with pinch zoom; controls stay reachable in short windows. */
@Composable
private fun ImagePreview(path: String?, data: String?, onPrevious: (() -> Unit)? = null, onNext: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, path, data) { value = null; value = loadImage(path, data, 2000) }
    var scale by remember(path, data) { mutableFloatStateOf(1f) }
    var offset by remember(path, data) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { dev.cpa.android.core.designsystem.CpaWindowContent {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(CpaIcons.Close, stringResource(R.string.close_preview), onDismiss)
                Spacer(Modifier.weight(1f))
                onPrevious?.let { GlassIconButton(CpaIcons.ChevronLeft, stringResource(R.string.previous_image), it); Spacer(Modifier.width(8.dp)) }
                onNext?.let { GlassIconButton(CpaIcons.ChevronRight, stringResource(R.string.next_image), it); Spacer(Modifier.width(8.dp)) }
                GlassIconButton(CpaIcons.Plus, stringResource(R.string.zoom_in), { scale = (scale + 1).coerceAtMost(5f) })
                Spacer(Modifier.width(8.dp))
                GlassIconButton(CpaIcons.Refresh, stringResource(R.string.reset), { scale = 1f; offset = Offset.Zero })
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 2000.dp).clipToBounds().transformable(transform), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it, stringResource(R.string.image_preview), Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) }
                    ?: Text(stringResource(R.string.image_loading_unavailable), color = Color.White)
            }
        }
    } }
}
