package app.livetoframe.personal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

internal data class GalleryPreviewSource(val photo: GalleryPhoto, val bounds: Rect?, val thumbnail: Bitmap?)

@Composable
internal fun GalleryLivePreview(
    source: GalleryPreviewSource,
    model: GalleryModel,
    dismiss: () -> Unit,
) {
    val photo = source.photo
    val cache = LocalContext.current.cacheDir
    var cover by remember(photo.key) { mutableStateOf<Bitmap?>(null) }
    var coverLoaded by remember(photo.key) { mutableStateOf(false) }
    var video by remember(photo.key) { mutableStateOf<PreparedPreview?>(null) }
    var videoVisible by remember(photo.key) { mutableStateOf(false) }
    var videoFrameReady by remember(photo.key) { mutableStateOf(false) }
    var videoFailed by remember(photo.key) { mutableStateOf(false) }
    var closing by remember(photo.key) { mutableStateOf(false) }
    var placed by remember(photo.key) { mutableStateOf(false) }
    var dialogOrigin by remember(photo.key) { mutableStateOf(Offset.Zero) }
    var opened by remember(photo.key) { mutableStateOf(false) }
    val previewOpened by rememberUpdatedState(opened)
    val zoom = remember(photo.key) { Animatable(0f) }
    var openingRatio by remember(photo.key) { mutableStateOf(photo.aspectRatio ?: 1f) }
    val coverRatio = cover?.let { it.width.toFloat() / it.height } ?: openingRatio
    val targetRatio = if (videoVisible && !closing) video?.aspectRatio ?: coverRatio else coverRatio
    val ease = CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f)
    val close: () -> Unit = { closing = true }

    LaunchedEffect(photo.key) {
        cover = model.previewImage(photo.uri)
        coverLoaded = true
    }
    LaunchedEffect(photo.key) {
        var directory: File? = null
        try {
            val work = kotlin.io.path.createTempDirectory(cache.toPath(), "preview-").toFile()
            directory = work
            video = model.preparePreview(photo, work)
            awaitCancellation()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            videoFailed = true
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { directory?.deleteRecursively() }
        }
    }
    LaunchedEffect(placed, closing) {
        if (closing) {
            zoom.animateTo(0f, tween(205, easing = ease))
            dismiss()
        } else if (placed) {
            openingRatio = coverRatio
            zoom.animateTo(1f, tween(270, easing = ease))
            opened = true
        }
    }
    LaunchedEffect(opened, videoFrameReady) {
        videoVisible = opened && videoFrameReady
    }

    val ratio by animateFloatAsState(
        if (!opened || abs(targetRatio / openingRatio - 1f) < 0.012f) openingRatio else targetRatio,
        tween(if (opened) 170 else 0, easing = ease), label = "preview aspect ratio",
    )
    val coverOpacity by animateFloatAsState(if (videoVisible && !closing) 0f else 1f,
        tween(140, easing = ease), label = "preview cover")
    val fullImageOpacity by animateFloatAsState(if (cover != null) 1f else 0f,
        tween(140, easing = ease), label = "preview photo")

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        val density = LocalDensity.current
        BoxWithConstraints(
            Modifier.fillMaxSize().onGloballyPositioned {
                val origin = it.positionOnScreen()
                if (origin.isSpecified) dialogOrigin = origin
                placed = true
            },
        ) {
            val fraction = zoom.value
            val mediaMaxWidth = (maxWidth - 34.dp).coerceAtLeast(1.dp)
            val mediaMaxHeight = (maxHeight - 68.dp).coerceAtLeast(1.dp)
            val targetWidth = with(density) {
                minOf(mediaMaxWidth, mediaMaxHeight * ratio).toPx()
            }
            val targetHeight = targetWidth / ratio
            val origin = source.bounds
            val targetLeft = (constraints.maxWidth - targetWidth) / 2
            val targetTop = (constraints.maxHeight - targetHeight) / 2
            val left = lerp(origin?.let { it.left - dialogOrigin.x } ?: targetLeft, targetLeft, fraction)
            val top = lerp(origin?.let { it.top - dialogOrigin.y } ?: targetTop, targetTop, fraction)
            val width = lerp(origin?.width ?: targetWidth * 0.96f, targetWidth, fraction)
            val height = lerp(origin?.height ?: targetHeight * 0.96f, targetHeight, fraction)
            Box(Modifier.matchParentSize().graphicsLayer { alpha = fraction * 0.77f }.background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { close() } })
            if (placed && fraction > 0f) {
                Box(
                    Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                        .graphicsLayer { alpha = if (origin == null) fraction else 1f }
                        .clip(RoundedCornerShape(lerp(3f, 18f, fraction).dp))
                        .background(Color(0xFF111214))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .semantics {
                            contentDescription = "实况预览"
                            customActions = listOf(CustomAccessibilityAction("关闭预览") { close(); true })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val prepared = video
                    if (placed && prepared != null && !videoFailed) {
                        // Keep the decoder surface at its fitted size while the outer clip animates.
                        val videoWidth = minOf(mediaMaxWidth, mediaMaxHeight * prepared.aspectRatio)
                        AndroidView(
                            modifier = Modifier.requiredSize(videoWidth, videoWidth / prepared.aspectRatio),
                            factory = { context ->
                                GalleryPreviewPlayer(context, prepared.file, prepared.videoStart, prepared.videoLength,
                                    onFrame = {
                                        videoFrameReady = true
                                        if (previewOpened) videoVisible = true
                                    },
                                    onComplete = { videoVisible = false },
                                    onFailure = { videoVisible = false; videoFailed = true },
                                )
                            },
                            update = { if (closing) it.pausePlayback() },
                            onRelease = { it.releasePlayer() },
                        )
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverOpacity }.background(Color(0xFF111214)),
                        contentAlignment = Alignment.Center) {
                        if (fullImageOpacity < 1f || fraction < 1f) source.thumbnail?.let {
                            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        cover?.let {
                            Image(it.asImageBitmap(), null,
                                Modifier.fillMaxSize().graphicsLayer { alpha = fullImageOpacity * fraction },
                                contentScale = ContentScale.Fit)
                        }
                        if (cover == null && opened && !closing) {
                            if (!coverLoaded && source.thumbnail == null) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else if (coverLoaded && source.thumbnail == null) {
                                Text("暂时无法读取照片", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                    Text("实况", Modifier.align(Alignment.TopStart).padding(10.dp)
                        .graphicsLayer { alpha = fraction * fraction }
                        .background(Color.Black.copy(alpha = 0.54f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    if (videoFailed && opened && !closing) {
                        Text("暂时无法播放实况", Modifier.align(Alignment.BottomCenter).padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private class GalleryPreviewPlayer(
    context: Context,
    private val file: File,
    private val videoStart: Long,
    private val videoLength: Long,
    private val onFrame: () -> Unit,
    private val onComplete: () -> Unit,
    private val onFailure: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var waitingForFrame = false
    private var playbackAllowed = true
    private var completed = false

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (completed || !playbackAllowed) return
        val surface = Surface(texture)
        val preview = MediaPlayer()
        player = preview
        try {
            preview.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            preview.setSurface(surface)
            java.io.FileInputStream(file).use {
                preview.setDataSource(it.fd, videoStart, videoLength)
            }
            preview.setOnPreparedListener { if (playbackAllowed) it.start() }
            preview.setOnInfoListener { _, info, _ ->
                if (info == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && playbackAllowed) waitingForFrame = true
                false
            }
            preview.setOnCompletionListener {
                completed = true
                onComplete()
            }
            preview.setOnErrorListener { _, _, _ -> onFailure(); true }
            preview.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            onFailure()
        } finally {
            surface.release()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!waitingForFrame) return
        waitingForFrame = false
        onFrame()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    fun pausePlayback() {
        playbackAllowed = false
        val preview = player ?: return
        runCatching { if (preview.isPlaying) preview.pause() }
    }

    fun releasePlayer() {
        player?.release()
        player = null
        waitingForFrame = false
    }
}
