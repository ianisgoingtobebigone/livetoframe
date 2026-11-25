package app.livetoframe.personal

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

internal const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

internal data class DiagnosticResult(
    val name: String,
    val status: String,
    val detail: String,
    val copy: Uri? = null,
    val operation: String? = null,
    val source: Uri? = null,
    val captureTime: Long? = null,
)

internal enum class SaveStage(val label: String) {
    PREPARING("准备读取照片"), WRITING("写入静态副本"), VERIFYING("回读核验静态副本"),
}

internal class DiagnosticImport(private val context: Context) {
    private val resolver get() = context.contentResolver

    suspend fun process(
        input: Uri,
        fromGallery: Boolean = false,
        confirmSource: () -> Unit = {},
        onTarget: (Uri, String, String) -> Unit = { _, _, _ -> },
        onStage: suspend (SaveStage) -> Unit = {},
    ): DiagnosticResult {
        var name = "原文件"
        var output: Uri? = null
        var published = false
        var stage = "读取原片"
        var work: File? = null
        val started = SystemClock.elapsedRealtime()
        try {
            if (fromGallery) confirmSource()
            name = displayName(input) ?: name
            if (fromGallery && !isGalleryImage(input) || !fromGallery && !isLocalImageDocument(input)) {
                throw UnsupportedPhoto("请将原文件放到本机存储后，在系统文件选择器左侧选择本机存储")
            }
            val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "motion-").toFile()
            work = directory
            val source = directory.resolve("source")
            val sourceHasher = MessageDigest.getInstance("SHA-256")
            openOriginal(input)?.use { incoming ->
                source.outputStream().buffered().use { outgoing ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = incoming.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > 512L * 1024 * 1024) throw UnsupportedPhoto("单个样本超过 512 MB")
                        sourceHasher.update(buffer, 0, read)
                        outgoing.write(buffer, 0, read)
                    }
                }
            } ?: error("系统未提供原文件")
            stage = "解析容器"
            val plan = MotionJpeg.inspect(source)
            checkVideo(source, plan)
            val still = directory.resolve("still.jpg")
            MotionJpeg.writeStill(source, plan, still)
            val expected = still.inputStream().use(::digest)
            val originalBounds = source.inputStream().use(::imageBounds)
            check(still.inputStream().use(::imageBounds) == originalBounds) { "静态图尺寸发生变化" }
            val prepared = SystemClock.elapsedRealtime()

            stage = "写入静态副本"
            onStage(SaveStage.WRITING)
            if (fromGallery) confirmSource()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, staticPhotoName(name))
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LiveToFrame/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在系统相册创建副本")
            output = target
            onTarget(target, expected.toHex(), sourceHasher.digest().toHex())
            resolver.openOutputStream(target, "w")?.use { targetStream ->
                still.inputStream().use { it.copyTo(targetStream) }
            } ?: error("系统未提供写入通道")
            onStage(SaveStage.VERIFYING)
            check(readDigest(target).contentEquals(expected)) { "发布前回读字节不一致" }
            val written = SystemClock.elapsedRealtime()

            stage = "发布并回读"
            val updated = resolver.update(target, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            check(updated == 1) { "系统未确认发布" }
            published = true
            check(readDigest(target).contentEquals(expected)) { "发布后回读字节不一致" }
            resolver.openInputStream(target)?.use { stream ->
                val bounds = imageBounds(stream)
                check(bounds == originalBounds) { "系统副本尺寸不一致" }
            } ?: error("系统副本无法打开")
            val sampleSize = Integer.highestOneBit(
                maxOf(originalBounds.first, originalBounds.second) / 1024
            ).coerceAtLeast(1)
            val preserveGainMap = Build.VERSION.SDK_INT >= 34 && (plan.hasGainMap ||
                plan.auxiliaryXmp != null && ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
                    decoder.setTargetSampleSize(sampleSize)
                }.let { original -> try { original.hasGainmap() } finally { original.recycle() } })
            val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, target)) { decoder, _, _ ->
                decoder.setTargetSampleSize(sampleSize)
            }
            try {
                if (preserveGainMap && Build.VERSION.SDK_INT >= 34) {
                    check(image.hasGainmap()) { "系统解码未返回 HDR 增益图" }
                }
            } finally {
                image.recycle()
            }
            val actualName = displayName(target) ?: error("系统未返回副本文件名")
            val verified = SystemClock.elapsedRealtime()
            val bytes = source.length() - still.length()
            return DiagnosticResult(name, "副本回读通过",
                "${plan.profile} · ${originalBounds.first} × ${originalBounds.second}\n" +
                    "系统文件名：$actualName\n原片 ${source.length()} B · 副本 ${still.length()} B · 字节差 $bytes B\n" +
                    "准备 ${prepared - started} ms · 写入 ${written - prepared} ms · 回读 ${verified - written} ms", target)
        } catch (unsupported: UnsupportedPhoto) {
            return DiagnosticResult(name, "已跳过", unsupported.message.orEmpty())
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return DiagnosticResult(name, if (published) "待核验" else "处理失败",
                "$stage：${failure.message ?: failure.javaClass.simpleName}", output.takeIf { published })
        } finally {
            if (!published) output?.let { created ->
                // Restrict cleanup to this operation's newly inserted, still-pending row.
                runCatching {
                    resolver.delete(created, "${MediaStore.MediaColumns.IS_PENDING} = 1", null)
                }
            }
            work?.deleteRecursively()
        }
    }

    private fun openOriginal(uri: Uri): InputStream? {
        if (uri.authority != MEDIA_DOCUMENTS_AUTHORITY && !isGalleryImage(uri)) {
            return resolver.openInputStream(uri)
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw UnsupportedPhoto("请允许读取所选照片的原始位置元数据，再选一次原片")
        }
        val mediaUri = if (isGalleryImage(uri)) uri else MediaStore.getMediaUri(context, uri)
            ?: throw UnsupportedPhoto("系统未提供此照片的本机原始文件")
        return resolver.openFileDescriptor(MediaStore.setRequireOriginal(mediaUri), "r")
            ?.let(ParcelFileDescriptor::AutoCloseInputStream)
    }

    private fun isLocalImageDocument(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isDocumentUri(context, uri)) {
            return false
        }
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> true
            MEDIA_DOCUMENTS_AUTHORITY -> {
                val id = DocumentsContract.getDocumentId(uri)
                id.startsWith("image:") && id.substringAfter(':').toLongOrNull()?.let { it > 0 } == true
            }
            else -> false
        }
    }

    private fun isGalleryImage(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.authority == MediaStore.AUTHORITY &&
            uri.pathSegments.let {
                it.size == 4 && it[0] == MediaStore.VOLUME_EXTERNAL_PRIMARY &&
                    it[1] == "images" && it[2] == "media" && it[3].toLongOrNull()?.let { id -> id > 0 } == true
            }

    fun recover(name: String, target: Uri, expectedHash: String): DiagnosticResult {
        val pending = resolver.query(target, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
        check(pending == 0 && readDigest(target).toHex() == expectedHash) { "副本尚未完成发布或校验" }
        check(resolver.openInputStream(target)?.use(::imageBounds) != null) { "副本无法打开" }
        return DiagnosticResult(name, "副本回读通过", "已从中断记录恢复并核对副本字节", target)
    }

    /** Remove only a journal-linked, app-owned row that was never published. */
    fun discardInterruptedTarget(target: Uri): Boolean {
        val values = resolver.query(target, interruptedProjection, null, null, null)?.use {
            if (!it.moveToFirst()) return false
            InterruptedTarget(it.getInt(0) != 0, it.getString(1), it.getString(2))
        } ?: return false
        if (!values.pending || values.owner != context.packageName ||
            values.relativePath != "Pictures/LiveToFrame/") return false
        return resolver.delete(target, "${MediaStore.MediaColumns.IS_PENDING} = 1", null) == 1
    }

    private data class InterruptedTarget(val pending: Boolean, val owner: String?, val relativePath: String?)
    private val interruptedProjection = arrayOf(MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun readDigest(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use(::digest) ?: error("无法回读副本")

    private fun digest(input: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return hash.digest()
            hash.update(buffer, 0, count)
        }
    }

    private fun imageBounds(input: InputStream): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        check(options.outWidth > 0 && options.outHeight > 0) { "无法识别 JPEG 尺寸" }
        return options.outWidth to options.outHeight
    }

    private fun checkVideo(source: File, plan: StaticPlan) {
        val extractor = MediaExtractor()
        try {
            FileInputStream(source).use { input ->
                extractor.setDataSource(input.fd, plan.videoStart, plan.videoLength)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw UnsupportedPhoto("嵌入的 MP4 没有可读的视频轨道")
                extractor.selectTrack(track)
                if (extractor.sampleTime < 0) throw UnsupportedPhoto("嵌入的视频轨道没有样本")
            }
        } finally {
            extractor.release()
        }
    }
}

internal fun staticPhotoName(name: String): String =
    name.substringBeforeLast('.').replace(Regex("[/\\\\\\p{Cntrl}]"), "_")
        .trim().take(150).ifBlank { "静态照片" } + ".jpg"
