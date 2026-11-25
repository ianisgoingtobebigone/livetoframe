package app.livetoframe.personal

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

internal data class GalleryPhoto(
    val uri: Uri,
    val name: String,
    val captureTime: Long,
    val generation: Long,
    val size: Long,
    val favorite: Boolean,
    val providerVersion: String,
    val aspectRatio: Float?,
    val estimate: Long? = null,
) {
    val key: String get() = uri.toString()
    val date: LocalDate? = captureTime.takeIf { it > 0 }
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    val dayKey: String = date?.toString() ?: "unknown"
}

internal data class PreparedPreview(
    val file: File,
    val videoStart: Long,
    val videoLength: Long,
    val aspectRatio: Float,
)

internal class GalleryMedia(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val projection = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.GENERATION_MODIFIED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_FAVORITE,
        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
    )
    private val cacheFile = AtomicFile(context.cacheDir.resolve("motion-index-v1"))
    private fun providerVersion() = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)

    suspend fun scan(
        access: PhotoAccess,
        publish: (suspend (List<GalleryPhoto>, String?) -> Unit)?,
    ): List<GalleryPhoto> = scanLock.withLock {
        val started = SystemClock.elapsedRealtime()
        val version = providerVersion()
        val scanContext = currentCoroutineContext()
        val index = readIndex(version)
        // Only publish items returned by the current permission-scoped MediaStore query.
        val visible = mutableListOf<GalleryPhoto>()
        val seen = if (access == PhotoAccess.FULL) hashSetOf<Long>() else null
        var scanned = 0
        var publishedCount = 0
        var publishedDay: String? = null
        var lastPublication = 0L
        var dirty = false
        var recordsSinceCheckpoint = 0
        try {
            val cursor = resolver.query(collection, projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
                arrayOf("DCIM/Camera/", "image/jpeg"),
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC")
                ?: throw IOException("系统未返回相册数据")
            cursor.use {
                while (true) {
                    scanContext.ensureActive()
                    val batchSize = if (visible.isEmpty()) 16 else 128
                    val batch = buildList<ScanRow>(batchSize) {
                        repeat(batchSize) {
                            if (!cursor.moveToNext()) return@buildList
                            val id = cursor.getLong(0)
                            val uri = ContentUris.withAppendedId(collection, id)
                            add(ScanRow(id, cursor.photo(uri, version)))
                        }
                    }
                    if (batch.isEmpty()) break
                    val misses = batch.filter { index[it.id, it.photo.generation, it.photo.size] == null }
                    val results = if (misses.isEmpty()) emptyArray() else probeBatch(misses)
                    var nextMiss = 0
                    for ((id, photo) in batch) {
                        seen?.add(id)
                        val record = index[id, photo.generation, photo.size] ?: results[nextMiss++].also {
                            if (it != null && index.put(id, it)) {
                                dirty = true
                                recordsSinceCheckpoint++
                            }
                        }
                        record?.candidate?.let { visible += photo.copy(estimate = it.estimatedBytes) }
                    }
                    scanned += batch.size
                    val now = SystemClock.elapsedRealtime()
                    val partialDay = batch.last().photo.dayKey.takeIf {
                        batch.size == batchSize && it == visible.lastOrNull()?.dayKey
                    }
                    if (publish != null && visible.isNotEmpty() &&
                        (visible.size != publishedCount || partialDay != publishedDay) &&
                        (publishedCount == 0 || now - lastPublication >= 200)) {
                        if (publishedCount == 0) Log.d("LiveToFrameScan", "first photos in ${now - started} ms")
                        publish(visible.toList(), partialDay)
                        publishedCount = visible.size
                        publishedDay = partialDay
                        lastPublication = now
                    }
                    if (recordsSinceCheckpoint >= 1024) {
                        dirty = !writeIndex(index)
                        recordsSinceCheckpoint = 0
                    }
                    if (batch.size < batchSize) break
                }
            }
            if (seen != null && index.retain(seen)) dirty = true
            Log.d("LiveToFrameScan", "${visible.size}/$scanned photos in ${SystemClock.elapsedRealtime() - started} ms")
            visible
        } finally {
            if (dirty) writeIndex(index)
        }
    }

    private data class ScanRow(val id: Long, val photo: GalleryPhoto)

    private fun Cursor.photo(uri: Uri, version: String): GalleryPhoto {
        val width = getInt(6)
        val height = getInt(7)
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else null
        return GalleryPhoto(uri, getString(1).orEmpty(), getLong(2), getLong(3), getLong(4), getInt(5) != 0,
            version, ratio)
    }

    fun resolveBatchPhotos(items: List<SavedBatchItem>, protectFavorites: Boolean): List<GalleryPhoto> {
        val version = providerVersion()
        return items.map { item ->
            if (item.providerVersion != version) throw UnsupportedPhoto("相册索引已变化，无法继续这个批次")
            val uri = Uri.parse(item.source)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.photo(uri, version) else null
            }?.takeIf { it.generation == item.generation && it.size == item.size &&
                (!protectFavorites || !it.favorite) }
                ?: throw UnsupportedPhoto("上次批次中有照片已变化、受收藏保护或不在当前授权范围内")
        }
    }

    companion object {
        private val scanLock = Mutex()
    }

    private suspend fun probeBatch(rows: List<ScanRow>): Array<MotionScanRecord?> = coroutineScope {
        val results = arrayOfNulls<MotionScanRecord>(rows.size)
        val workers = minOf(4, rows.size)
        (0 until workers).map { worker ->
            async {
                val job = currentCoroutineContext()
                for (index in worker until rows.size step workers) {
                    job.ensureActive()
                    val photo = rows[index].photo
                    results[index] = try {
                        resolver.openInputStream(photo.uri)?.use {
                            MotionScanRecord(photo.generation, photo.size, MotionHeader.probe(it, photo.size))
                        }
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                }
            }
        }.awaitAll()
        results
    }

    private fun readIndex(version: String): MotionScanIndex = try {
        DataInputStream(cacheFile.openRead().buffered()).use { MotionScanIndex.read(version, it) }
    } catch (_: IOException) {
        MotionScanIndex(version)
    }

    private fun writeIndex(index: MotionScanIndex): Boolean {
        val output = try { cacheFile.startWrite() } catch (_: IOException) { return false }
        return try {
            val buffered = DataOutputStream(output.buffered())
            index.write(buffered)
            buffered.flush()
            cacheFile.finishWrite(output)
            true
        } catch (_: IOException) {
            cacheFile.failWrite(output)
            false
        }
    }

    fun previewImage(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) {
            decoder, info, _ ->
        val (width, height) = info.size.let { it.width to it.height }
        val scale = 1600f / maxOf(width, height)
        if (scale < 1f) {
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1))
        }
    }

    suspend fun preparePreview(photo: GalleryPhoto, directory: File): PreparedPreview {
        require(photo.size in 1..(512L * 1024 * 1024)) { "文件过大" }
        val source = directory.resolve("source.jpg")
        val buffer = ByteArray(64 * 1024)
        resolver.openInputStream(photo.uri)?.use { input ->
            source.outputStream().buffered().use { output ->
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= photo.size) { "照片在读取期间发生变化" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取原片")
        val range = MotionJpeg.previewVideo(source)
        val metadata = MediaMetadataRetriever()
        try {
            java.io.FileInputStream(source).use {
                metadata.setDataSource(it.fd, range.start, range.length)
            }
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            check(width > 0 && height > 0) { "视频尺寸不可用" }
            val aspectRatio = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
            return PreparedPreview(source, range.start, range.length, aspectRatio)
        } finally {
            metadata.release()
        }
    }

    fun validate(photo: GalleryPhoto, protectFavorites: Boolean) {
        if (providerVersion() != photo.providerVersion) throw UnsupportedPhoto("相册索引发生变化，请刷新后重选")
        val valid = resolver.query(photo.uri, projection, null, null, null)?.use {
            it.moveToFirst() && it.getLong(3) == photo.generation && it.getLong(4) == photo.size &&
                (!protectFavorites || it.getInt(5) == 0)
        } == true
        if (!valid) throw UnsupportedPhoto("原片已改动、受收藏保护或访问权限已变化")
    }
}

internal data class MotionCandidate(val estimatedBytes: Long?)

internal object MotionHeader {
    private val xmpSignature = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private val flags = Regex("""(?:^|\s)[\w-]+:(?:MotionPhoto|MicroVideo)\s*=\s*["']1["']""")
    private val offset = Regex("""[\w-]+:MicroVideoOffset\s*=\s*["'](\d+)["']""")
    private val motionItem = Regex("""<[^<>]+(?:\w+:Semantic\s*=\s*["']MotionPhoto["'])[^<>]*>""")
    private val length = Regex("""[\w-]+:Length\s*=\s*["'](\d+)["']""")

    fun probe(source: InputStream, size: Long): MotionCandidate? {
        val input = DataInputStream(source.buffered())
        if (input.readUnsignedShort() != 0xffd8) return null
        var scanned = 2
        while (scanned < 1024 * 1024) {
            if (input.readUnsignedByte() != 0xff) return null
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9 || marker !in 0xc0..0xfe) return null
            val count = input.readUnsignedShort() - 2
            if (count < 0) return null
            scanned += count + 4
            var remaining = count
            if (marker == 0xe1 && count >= xmpSignature.size) {
                val prefix = ByteArray(xmpSignature.size).also(input::readFully)
                remaining -= prefix.size
                if (prefix.contentEquals(xmpSignature)) {
                    val text = ByteArray(remaining).also(input::readFully).decodeToString()
                    if (text.contains("http://ns.google.com/photos/1.0/camera/") && flags.containsMatchIn(text)) {
                        val bytes = motionItem.find(text)?.value?.let { length.find(it)?.groupValues?.get(1) }
                            ?: offset.find(text)?.groupValues?.get(1)
                        return MotionCandidate(bytes?.toLongOrNull()?.takeIf { it in 1 until size })
                    }
                    continue
                }
            }
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                if (skipped == 0) { input.readByte(); remaining-- } else remaining -= skipped
            }
        }
        return null
    }
}
