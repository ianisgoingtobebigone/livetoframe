package app.livetoframe.personal

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest

internal const val VERIFIED_COPY = "副本回读通过"

internal data class TrashCandidate(
    val operation: String,
    val name: String,
    val source: Uri,
    val copy: Uri,
    val volumeVersion: String,
    val sourceGeneration: Long,
    val sourceSize: Long,
    val sourceHash: String,
    val copyGeneration: Long,
    val copyHash: String,
    val copyName: String? = null,
)

internal enum class TrashStatus { REQUESTED, IN_TRASH, NEEDS_CHECK, RESTORED, PRESENT }

internal data class TrashCheck(val status: TrashStatus, val identity: TrashCandidate? = null)

internal data class TrashItem(val operation: String, val name: String)

internal data class PreparedTrash(
    val intent: PendingIntent?,
    val operations: List<String>,
    val blocked: List<String>,
)

internal object TrashPolicy {
    fun verified(status: String, hasCopyIdentity: Boolean): Boolean =
        status == VERIFIED_COPY && hasCopyIdentity

    fun confirmedStatus(previous: TrashStatus, originalTrashed: Boolean?, copyVerified: Boolean): TrashStatus =
        when {
            originalTrashed == null || !copyVerified -> TrashStatus.NEEDS_CHECK
            originalTrashed -> TrashStatus.IN_TRASH
            previous == TrashStatus.IN_TRASH || previous == TrashStatus.RESTORED -> TrashStatus.RESTORED
            else -> TrashStatus.PRESENT
        }

    fun originalUnchanged(expectedGeneration: Long, actualGeneration: Long, expectedSize: Long,
        actualSize: Long, favorite: Boolean, pending: Boolean, trashed: Boolean): Boolean =
        !favorite && !pending && !trashed && expectedGeneration == actualGeneration &&
            expectedSize > 0 && expectedSize == actualSize
}

internal class TrashCoordinator(private val context: Context) {
    private val resolver get() = context.contentResolver

    private data class Row(
        val name: String,
        val generation: Long,
        val size: Long,
        val favorite: Boolean,
        val pending: Boolean,
        val trashed: Boolean,
        val relativePath: String?,
        val absolutePath: String?,
        val owner: String?,
        val mime: String?,
    )

    fun captureCopyGeneration(uri: Uri): Long? {
        if (!isImageUri(uri, allowExternalAlias = true)) return null
        return row(uri)?.takeIf(::validCopy)?.generation
    }

    fun prepare(
        candidates: List<TrashCandidate>,
        restoredOperations: Set<String> = emptySet(),
    ): PreparedTrash {
        val started = SystemClock.elapsedRealtime()
        val blocked = mutableListOf<String>()
        var identityMs = 0L
        var metadataMs = 0L
        val confirmed = candidates.distinctBy { it.source }.mapNotNull { saved ->
            val result = runCatching {
                val identityStarted = SystemClock.elapsedRealtime()
                val candidate = if (saved.operation in restoredOperations) refreshRestoredIdentity(saved)
                    else currentIdentity(saved)
                identityMs += SystemClock.elapsedRealtime() - identityStarted
                val metadataStarted = SystemClock.elapsedRealtime()
                checkMetadata(candidate)
                metadataMs += SystemClock.elapsedRealtime() - metadataStarted
                candidate
            }
            result.onFailure { blocked += "${saved.name} · ${it.message ?: "无法读取原件或副本"}" }.getOrNull()
        }.distinctBy { it.source }.filter { candidate ->
            val rechecked = runCatching { checkMetadata(candidate) }.isSuccess
            if (!rechecked) blocked += "${candidate.name} · 提交前相册状态发生变化"
            rechecked
        }
        if (confirmed.isEmpty()) {
            Log.i("LiveToFrameTrashTiming", "preflight ${candidates.size} candidates, 0 eligible, identity ${identityMs}ms, metadata ${metadataMs}ms, total ${SystemClock.elapsedRealtime() - started}ms")
            return PreparedTrash(null, emptyList(), blocked)
        }
        val requestStarted = SystemClock.elapsedRealtime()
        val pendingIntent = MediaStore.createTrashRequest(resolver, confirmed.map { it.source }, true)
        Log.i("LiveToFrameTrashTiming", "preflight ${candidates.size} candidates, ${confirmed.size} eligible, identity ${identityMs}ms, metadata ${metadataMs}ms, create request ${SystemClock.elapsedRealtime() - requestStarted}ms, total ${SystemClock.elapsedRealtime() - started}ms")
        return PreparedTrash(pendingIntent, confirmed.map { it.operation }, blocked)
    }

    private fun refreshRestoredIdentity(saved: TrashCandidate): TrashCandidate {
        val currentVersion = version()
        val source = row(saved.source) ?: error("恢复后的原件不可访问")
        val copy = row(saved.copy) ?: error("静态副本不可访问")
        check(source.name == saved.name && source.relativePath == "DCIM/Camera/" && source.mime == "image/jpeg" &&
            !source.favorite && !source.pending && !source.trashed && source.size == saved.sourceSize) {
            "恢复后的原件状态不符合核对条件"
        }
        check(validCopy(copy) && copy.generation == saved.copyGeneration) { "静态副本状态已变化" }
        check(fingerprint(MediaStore.setRequireOriginal(saved.source)) == saved.sourceHash &&
            fingerprint(saved.copy) == saved.copyHash) { "恢复后的原件或静态副本内容已变化" }
        check(version() == currentVersion) { "相册索引仍在变化" }
        return saved.copy(volumeVersion = currentVersion, sourceGeneration = source.generation)
    }

    fun checkOutcome(saved: TrashCandidate, previous: TrashStatus): TrashCheck = runCatching {
        val candidate = currentIdentity(saved)
        val source = row(candidate.source)
        val copy = row(candidate.copy)
        val originalTrashed = source?.takeIf { actual ->
            validSource(actual, candidate) &&
                row(candidate.source) == actual
        }?.trashed
        val copyVerified = copy != null && validCopy(copy) && copy.generation == candidate.copyGeneration &&
            copy.size > 0 && row(candidate.copy) == copy
        check(version() == candidate.volumeVersion) { "相册索引仍在变化" }
        val confirmed = TrashPolicy.confirmedStatus(previous, originalTrashed, copyVerified)
        TrashCheck(confirmed, candidate.takeUnless { confirmed == TrashStatus.NEEDS_CHECK })
    }.getOrElse { TrashCheck(TrashStatus.NEEDS_CHECK) }

    private fun version(): String = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun currentIdentity(saved: TrashCandidate): TrashCandidate {
        val currentVersion = version()
        if (saved.volumeVersion == currentVersion) return saved
        val source = row(saved.source)
        val copyRow = row(saved.copy)
        if (source != null && copyRow != null && validSource(source, saved) && validCopy(copyRow) &&
            (source.generation == saved.sourceGeneration || source.trashed) &&
            copyRow.generation == saved.copyGeneration) {
            return saved.copy(volumeVersion = currentVersion)
        }
        val original = uniqueMatch("_display_name = ?", arrayOf(saved.name)) { uri, row ->
            validSource(row, saved) && fingerprint(MediaStore.setRequireOriginal(uri)) == saved.sourceHash
        } ?: error("相册索引已变化，无法唯一核对原件")
        val copyName = saved.copyName ?: staticPhotoName(saved.name)
        val stem = copyName.substringBeforeLast('.').replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val copyMatch = uniqueMatch(
            "relative_path = ? AND (_display_name = ? OR _display_name LIKE ? ESCAPE '!')",
            arrayOf("Pictures/LiveToFrame/", copyName, "$stem (%).jpg"),
        ) { uri, row -> validCopy(row) && fingerprint(uri) == saved.copyHash }
            ?: error("相册索引已变化，无法唯一核对静态副本")
        check(original.first != copyMatch.first && version() == currentVersion) { "相册索引仍在变化" }
        return saved.copy(source = original.first, copy = copyMatch.first, volumeVersion = currentVersion,
            sourceGeneration = original.second.generation, copyGeneration = copyMatch.second.generation,
            copyName = copyMatch.second.name)
    }

    private fun uniqueMatch(
        selection: String, args: Array<String>, matches: (Uri, Row) -> Boolean,
    ): Pair<Uri, Row>? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val query = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }
        val ids = resolver.query(collection, arrayOf(BaseColumns._ID), query, null)?.use { cursor ->
            if (cursor.count > 32) return null
            buildList<Uri> { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        } ?: return null
        return ids.mapNotNull { uri -> row(uri)?.takeIf { matches(uri, it) }?.let { uri to it } }.singleOrNull()
    }

    private fun validSource(row: Row, saved: TrashCandidate): Boolean =
        row.name == saved.name && (row.trashed || row.relativePath == "DCIM/Camera/") && row.mime == "image/jpeg" &&
            !row.pending && row.size == saved.sourceSize

    private fun checkMetadata(candidate: TrashCandidate) {
        check(isImageUri(candidate.source) && isImageUri(candidate.copy, allowExternalAlias = true) &&
            candidate.source.lastPathSegment != candidate.copy.lastPathSegment) { "资产标识无效" }
        check(MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY) == candidate.volumeVersion) {
            "相册索引发生变化，请重新保存并核验"
        }
        val source = row(candidate.source) ?: error("原件不可访问")
        check(source.name == candidate.name && source.relativePath == "DCIM/Camera/" && source.mime == "image/jpeg" &&
            TrashPolicy.originalUnchanged(candidate.sourceGeneration, source.generation, candidate.sourceSize,
                source.size, source.favorite, source.pending, source.trashed)) {
            "原件有改动、已收藏或无法确认相册归属"
        }
        val copy = row(candidate.copy) ?: error("静态副本不可访问")
        check(validCopy(copy) && copy.generation == candidate.copyGeneration) {
            "静态副本有改动或无法确认相册归属"
        }
    }

    private fun validCopy(copy: Row) = !copy.pending && !copy.trashed && copy.size > 0 &&
        copy.relativePath == "Pictures/LiveToFrame/" && copy.owner == context.packageName &&
        copy.mime == "image/jpeg"

    private fun isImageUri(uri: Uri, allowExternalAlias: Boolean = false): Boolean {
        val segments = uri.pathSegments
        return uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && segments.size == 4 &&
            (segments[0] == MediaStore.VOLUME_EXTERNAL_PRIMARY ||
                (allowExternalAlias && segments[0] == MediaStore.VOLUME_EXTERNAL)) &&
            segments[1] == "images" && segments[2] == "media" &&
            segments[3].toLongOrNull()?.let { it > 0 } == true
    }

    private fun row(uri: Uri): Row? {
        val query = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(uri, projection, query, null)?.use {
            if (!it.moveToFirst()) null
            else Row(it.getString(0), it.getLong(1), it.getLong(2), it.getInt(3) != 0,
                it.getInt(4) != 0, it.getInt(5) != 0, it.getString(6), it.getString(7), it.getString(8), it.getString(9))
        }
    }

    private fun fingerprint(uri: Uri): String {
        val hash = MessageDigest.getInstance("SHA-256")
        resolver.openFileDescriptor(uri, "r")?.let(ParcelFileDescriptor::AutoCloseInputStream)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        } ?: error("无法读回文件")
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private val projection = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.MIME_TYPE,
    )
}
