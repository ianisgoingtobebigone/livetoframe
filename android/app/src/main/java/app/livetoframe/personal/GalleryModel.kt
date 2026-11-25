package app.livetoframe.personal

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

internal enum class PhotoAccess { NONE, LIMITED, FULL }

internal object SaveResumePolicy {
    fun reuseVerifiedCopy(status: String?): Boolean = status == VERIFIED_COPY

    fun needsReconcile(status: String?, hasTarget: Boolean): Boolean =
        status == "待核验" || (status == null && hasTarget)
}

internal data class GalleryState(
    val access: PhotoAccess = PhotoAccess.NONE,
    val photos: List<GalleryPhoto> = emptyList(),
    val selected: Set<String> = emptySet(),
    val scanning: Boolean = false,
    val partialDay: String? = null,
    val protectFavorites: Boolean = true,
    val saving: Boolean = false,
    val total: Int = 0,
    val activePhoto: String? = null,
    val saveStage: SaveStage = SaveStage.PREPARING,
    val results: List<DiagnosticResult> = emptyList(),
    val notice: String? = null,
    val galleryNotice: String? = null,
    val canResumeBatch: Boolean = false,
    val trashItems: List<TrashItem> = emptyList(),
    val trashStatuses: Map<String, TrashStatus> = emptyMap(),
    val checkingTrash: Boolean = false,
    val trashIntent: PendingIntent? = null,
    val trashNotice: String? = null,
)

internal class GalleryModel(application: Application) : AndroidViewModel(application) {
    private val media = GalleryMedia(application)
    private val converter = DiagnosticImport(application)
    private val preferences = application.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    private val journal = SaveJournal(application)
    private val trash = TrashCoordinator(application)
    private val current = MutableStateFlow(GalleryState(
        protectFavorites = preferences.getBoolean("protectFavorites", true),
    ))
    val state = current.asStateFlow()
    private var scanner: Job? = null
    private var deferredRefresh: PhotoAccess? = null
    private var pendingSave: Boolean? = null
    private var pendingTrashOperations = emptyList<String>()

    init {
        if (preferences.contains("selection")) preferences.edit().remove("selection").apply()
    }

    private val recovery = viewModelScope.launch {
        val (recovered, canResume) = withContext(Dispatchers.IO) {
            journal.recover(converter) to journal.hasActiveBatch()
        }
        if (current.value.results.isEmpty() && !current.value.saving) {
            current.value = current.value.copy(results = recovered, canResumeBatch = canResume, checkingTrash = true)
            try {
                reloadTrash(recheck = true)
            } finally {
                current.value = current.value.copy(checkingTrash = false)
            }
        }
    }

    fun refresh(access: PhotoAccess) {
        val snapshot = current.value
        val keepVisible = access == PhotoAccess.FULL && snapshot.access == PhotoAccess.FULL
        if (!keepVisible) thumbnailCache.evictAll()
        if (snapshot.saving) {
            deferredRefresh = access
            if (!keepVisible) {
                scanner?.cancel()
                current.value = snapshot.copy(access = access, photos = emptyList(), scanning = false, partialDay = null,
                    selected = if (access == PhotoAccess.NONE) emptySet() else snapshot.selected)
            }
            return
        }
        scanner?.cancel()
        if (access == PhotoAccess.NONE) {
            current.value = snapshot.copy(access = access, photos = emptyList(), scanning = false,
                partialDay = null, selected = emptySet())
            return
        }
        current.value = snapshot.copy(access = access, scanning = true, partialDay = null, galleryNotice = null,
            photos = if (keepVisible) snapshot.photos else emptyList())
        val publish: (suspend (List<GalleryPhoto>, String?) -> Unit)? = if (keepVisible && snapshot.photos.isNotEmpty()) {
            null
        } else {
            { photos, partialDay ->
                withContext(Dispatchers.Main) { current.value = current.value.copy(photos = photos, partialDay = partialDay) }
            }
        }
        scanner = viewModelScope.launch {
            try {
                val photos = withContext(Dispatchers.IO) { media.scan(access, publish) }
                val value = current.value
                if (value.selected.isNotEmpty()) {
                    val selected = value.selected.intersect(photos.mapTo(hashSetOf()) { it.key })
                    if (selected != value.selected) setSelection(selected)
                }
                current.value = current.value.copy(photos = photos, scanning = false, partialDay = null)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                current.value = current.value.copy(scanning = false, partialDay = null, galleryNotice = "无法读取完整相册，请检查照片权限或刷新")
            }
        }
    }

    private fun setSelection(selection: Set<String>) {
        current.value = current.value.copy(selected = selection)
    }

    fun toggle(keys: List<String>) {
        if (current.value.saving || keys.isEmpty()) return
        val selected = current.value.selected
        setSelection(if (keys.all { it in selected }) selected - keys.toSet() else selected + keys)
    }

    fun selectRange(base: Set<String>, keys: List<String>, selecting: Boolean) {
        if (!current.value.saving) setSelection(if (selecting) base + keys else base - keys.toSet())
    }

    fun clearSelection() = setSelection(emptySet())

    fun protectFavorites(protected: Boolean) {
        preferences.edit().putBoolean("protectFavorites", protected).apply()
        current.value = current.value.copy(protectFavorites = protected)
        if (protected) setSelection(current.value.selected - current.value.photos.filter { it.favorite }.map { it.key }.toSet())
    }

    fun deferSave(resumeBatch: Boolean) { pendingSave = resumeBatch }

    fun finishPermissionRequest(allowed: Boolean) {
        val resumeBatch = pendingSave ?: return
        pendingSave = null
        if (allowed) save(resumeBatch)
        else {
            val message = "读取原片的位置元数据需要授权"
            current.value = if (resumeBatch) current.value.copy(notice = message)
                else current.value.copy(galleryNotice = message)
        }
    }

    fun save(resumeBatch: Boolean) {
        val snapshot = current.value
        if (snapshot.saving || snapshot.checkingTrash || snapshot.access == PhotoAccess.NONE) return
        val selected = if (resumeBatch) emptyList() else snapshot.photos.filter {
            it.key in snapshot.selected && (!snapshot.protectFavorites || !it.favorite)
        }
        if (resumeBatch && !snapshot.canResumeBatch || !resumeBatch && selected.isEmpty()) return
        current.value = snapshot.copy(saving = true, total = selected.size, results = emptyList(), notice = null,
            activePhoto = null, saveStage = SaveStage.PREPARING,
            galleryNotice = null, trashItems = emptyList(), trashStatuses = emptyMap(), trashNotice = null)
        viewModelScope.launch {
            var started = false
            try {
                recovery.join()
                val (photos, operations) = withContext(Dispatchers.IO) {
                    val photos = if (resumeBatch) {
                        val items = journal.activeBatch()
                        if (items.isEmpty()) throw UnsupportedPhoto("没有需要继续的保存批次")
                        media.resolveBatchPhotos(items, snapshot.protectFavorites)
                    } else selected
                    photos to journal.begin(photos)
                }
                started = true
                if (!resumeBatch) setSelection(current.value.selected - photos.mapTo(hashSetOf()) { it.key })
                current.value = current.value.copy(total = photos.size, canResumeBatch = true)
                for ((photo, operation) in photos.zip(operations)) {
                    current.value = current.value.copy(activePhoto = photo.name, saveStage = SaveStage.PREPARING)
                    val result = withContext(Dispatchers.IO) {
                        val previous = journal.resume(operation, photo.name, converter)
                        if (previous != null) previous else {
                            journal.start(operation, photo)
                            val outcome = converter.process(photo.uri, fromGallery = true,
                                confirmSource = { media.validate(photo, snapshot.protectFavorites) },
                                onTarget = { target, hash, sourceHash -> journal.target(operation, target, hash, sourceHash) },
                                onStage = { stage ->
                                    withContext(Dispatchers.Main) { current.value = current.value.copy(saveStage = stage) }
                                })
                            val generation = if (outcome.status == VERIFIED_COPY) outcome.copy?.let {
                                runCatching { trash.captureCopyGeneration(it) }.getOrNull()
                            } else null
                            journal.finish(operation, outcome, generation)
                            outcome
                        }
                    }
                    current.value = current.value.let {
                        it.copy(results = it.results + result.copy(operation = operation,
                            source = photo.uri, captureTime = photo.captureTime.takeIf { time -> time > 0 }))
                    }
                }
                val unresolved = current.value.results.any { it.status == "待核验" || it.status == "处理失败" }
                if (!unresolved) {
                    withContext(Dispatchers.IO) { journal.completeBatch() }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val message = if (failure is UnsupportedPhoto) failure.message
                    else "无法确认保存记录。请先核对已经保存的副本。"
                current.value = current.value.copy(
                    results = if (started) current.value.results else snapshot.results,
                    notice = message,
                )
            } finally {
                reloadTrash(recheck = false)
                val canResume = withContext(Dispatchers.IO) { journal.hasActiveBatch() }
                current.value = current.value.copy(saving = false, canResumeBatch = canResume, activePhoto = null)
                deferredRefresh?.let {
                    deferredRefresh = null
                    refresh(it)
                }
            }
        }
    }

    fun requestTrash(operations: Set<String>) {
        if (current.value.checkingTrash || current.value.saving || operations.isEmpty()) return
        val requestStarted = SystemClock.elapsedRealtime()
        current.value = current.value.copy(checkingTrash = true, trashNotice = null)
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val entries = journal.trashEntries().filter { (candidate, status) ->
                        candidate.operation in operations &&
                            (status == null || status == TrashStatus.PRESENT || status == TrashStatus.RESTORED)
                    }
                    val candidates = entries.map { it.first }
                    val restored = entries.filter { it.second == TrashStatus.RESTORED }.mapTo(hashSetOf()) { it.first.operation }
                    val result = trash.prepare(candidates, restoredOperations = restored)
                    if (result.intent != null) journal.setTrashStatus(result.operations, TrashStatus.REQUESTED)
                    result
                }
                pendingTrashOperations = prepared.operations
                Log.i("LiveToFrameTrashTiming", "button to prompt-ready ${SystemClock.elapsedRealtime() - requestStarted}ms, selected ${operations.size}, eligible ${prepared.operations.size}, blocked ${prepared.blocked.size}")
                current.value = current.value.copy(
                    trashIntent = prepared.intent,
                    trashNotice = if (prepared.blocked.isNotEmpty()) {
                        "以下原件已保留：\n" + prepared.blocked.joinToString("\n")
                    } else if (prepared.intent == null) "没有通过复核的原件" else null,
                    checkingTrash = prepared.intent != null,
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                current.value = current.value.copy(checkingTrash = false, trashNotice = "无法发起系统回收站确认，原件已保留")
            }
        }
    }

    fun takeTrashIntent(): PendingIntent? = current.value.trashIntent.also {
        current.value = current.value.copy(trashIntent = null)
    }

    fun finishTrash(approved: Boolean, access: PhotoAccess, launchFailed: Boolean = false) {
        val operations = pendingTrashOperations
        pendingTrashOperations = emptyList()
        viewModelScope.launch {
            val message = try {
                if (!approved) withContext(Dispatchers.IO) { journal.setTrashStatus(operations, null) }
                reloadTrash(recheck = approved || operations.isEmpty())
                when {
                    launchFailed -> "无法打开系统确认，原件已保留"
                    !approved -> "已取消移入回收站，原件已保留"
                    operations.any { current.value.trashStatuses[it] == TrashStatus.NEEDS_CHECK } ->
                        "部分原件暂无法读回，请在系统相册的回收站核对"
                    operations.any { current.value.trashStatuses[it] == TrashStatus.PRESENT } ->
                        "部分原件仍在相册，可以重新核对。"
                    else -> null
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                "回收站结果待核对，请在系统相册确认"
            }
            current.value = current.value.copy(
                checkingTrash = false,
                trashNotice = listOfNotNull(current.value.trashNotice, message).joinToString("\n").ifBlank { null },
            )
            refresh(access)
        }
    }

    fun recheckTrash() {
        if (current.value.saving || current.value.checkingTrash || current.value.trashStatuses.isEmpty()) return
        current.value = current.value.copy(checkingTrash = true)
        viewModelScope.launch {
            try {
                reloadTrash(recheck = true)
            } finally {
                current.value = current.value.copy(checkingTrash = false)
            }
        }
    }

    private suspend fun reloadTrash(recheck: Boolean) {
        val snapshot = current.value
        val operations = snapshot.results.mapNotNullTo(hashSetOf()) { it.operation }
        val entries = try {
            withContext(Dispatchers.IO) {
                journal.trashEntries().mapNotNull { (candidate, status) ->
                    if (candidate.operation !in operations) return@mapNotNull null
                    if (!recheck || status == null) return@mapNotNull candidate to status
                    val check = trash.checkOutcome(candidate, status)
                    val identity = check.identity ?: candidate
                    runCatching { journal.recordTrashCheck(identity, check.status) }
                    identity to check.status
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            current.value = current.value.copy(
                trashItems = emptyList(),
                trashStatuses = snapshot.trashStatuses.mapValues { TrashStatus.NEEDS_CHECK },
                trashNotice = "回收站结果待核对，请在系统相册确认",
            )
            return
        }
        val byOperation = entries.associateBy { it.first.operation }
        current.value = current.value.copy(
            results = current.value.results.map { result ->
                val identity = byOperation[result.operation]?.first
                if (identity == null) result else result.copy(source = identity.source, copy = identity.copy)
            },
            trashItems = entries.filter { it.second == null || it.second == TrashStatus.PRESENT || it.second == TrashStatus.RESTORED }
                .map { TrashItem(it.first.operation, it.first.name) },
            trashStatuses = entries.mapNotNull { (candidate, status) -> status?.let { candidate.operation to it } }.toMap(),
        )
    }

    private val thumbnailSlots = Semaphore(4)
    private val thumbnailCache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    suspend fun thumbnail(photo: GalleryPhoto, pixels: Int): android.graphics.Bitmap? =
        loadThumbnail(photo.uri, pixels, "${photo.providerVersion}:${photo.key}:${photo.generation}:$pixels")

    suspend fun thumbnail(uri: Uri, pixels: Int): android.graphics.Bitmap? =
        loadThumbnail(uri, pixels, "$uri:$pixels")

    private suspend fun loadThumbnail(uri: Uri, pixels: Int, key: String): android.graphics.Bitmap? {
        thumbnailCache.get(key)?.let { return it }
        return thumbnailSlots.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.loadThumbnail(uri, android.util.Size(pixels, pixels), null)
                        .also { thumbnailCache.put(key, it) }
                }.getOrNull()
            }
        }
    }

    suspend fun preparePreview(photo: GalleryPhoto, directory: java.io.File) = withContext(Dispatchers.IO) {
        media.preparePreview(photo, directory)
    }

    suspend fun previewImage(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { media.previewImage(uri) }.getOrNull()
    }
}

internal data class SavedBatchItem(
    val source: String,
    val generation: Long,
    val size: Long,
    val providerVersion: String,
    val operation: String,
)

private class SaveJournal(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.getSharedPreferences("save-journal", Context.MODE_PRIVATE)

    fun activeBatch(): List<SavedBatchItem> {
        val active = runCatching { JSONObject(store.getString("activeBatch", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val items = active.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { index ->
            val item = items.optJSONObject(index) ?: return emptyList()
            SavedBatchItem(item.optString("source"), item.optLong("generation", -1), item.optLong("size", -1),
                item.optString("providerVersion"), item.optString("operation"))
        }.takeIf { batch -> batch.all { it.source.isNotBlank() && it.operation.isNotBlank() } }.orEmpty()
    }

    fun hasActiveBatch(): Boolean = store.contains("activeBatch")

    fun begin(photos: List<GalleryPhoto>): List<String> {
        val bySource = activeBatch().associateBy { it.source }
        val canResume = bySource.size == photos.size && photos.all { photo ->
            bySource[photo.key]?.let { it.generation == photo.generation &&
                it.size == photo.size && it.providerVersion == photo.providerVersion } == true
        }
        val operations = photos.map { photo ->
            bySource[photo.key]?.operation?.takeIf { canResume } ?: store.getString(pendingKey(photo.key), null)
                ?: "operation:${java.util.UUID.randomUUID()}"
        }
        val priorOperations = recent()
        val editor = store.edit().putString("recent", JSONArray(operations).toString())
        val items = JSONArray()
        for ((photo, operation) in photos.zip(operations)) {
            items.put(JSONObject().put("source", photo.key).put("operation", operation)
                .put("generation", photo.generation).put("size", photo.size)
                .put("providerVersion", photo.providerVersion))
        }
        editor.putString("activeBatch", JSONObject().put("items", items).toString())
        for (operation in priorOperations - operations.toSet()) {
            val entry = read(operation) ?: continue
            if (entry.has("status") && entry.optString("status") != "待核验") editor.remove(operation)
        }
        check(editor.commit()) { "无法保存批次记录" }
        return operations
    }

    fun start(operation: String, photo: GalleryPhoto) {
        val entry = JSONObject().put("name", photo.name).put("source", photo.key)
            .put("generation", photo.generation).put("providerVersion", photo.providerVersion).put("size", photo.size)
            .put("captureTime", photo.captureTime)
        check(store.edit().putString(operation, entry.toString())
            .putString(pendingKey(photo.key), operation).commit()) { "无法保存写入意图" }
    }

    fun target(operation: String, target: Uri, hash: String, sourceHash: String) {
        val entry = read(operation) ?: error("缺少写入意图")
        write(operation, entry.put("target", target.toString()).put("hash", hash)
            .put("sourceHash", sourceHash).toString())
    }

    fun finish(operation: String, result: DiagnosticResult, copyGeneration: Long? = null) {
        val entry = read(operation) ?: error("缺少写入意图")
        if (copyGeneration != null) entry.put("copyGeneration", copyGeneration)
        val editor = store.edit().putString(operation, entry.put("status", result.status).put("detail", result.detail)
            .put("copy", result.copy?.toString().orEmpty()).toString())
        if (result.status != "待核验") editor.remove(pendingKey(entry.getString("source")))
        check(editor.commit()) { "无法保存核验结果" }
    }

    fun resume(operation: String, name: String, converter: DiagnosticImport): DiagnosticResult? {
        val entry = read(operation) ?: return null
        val status = entry.optString("status").takeIf { entry.has("status") }
        if (SaveResumePolicy.reuseVerifiedCopy(status)) {
            val copy = entry.optString("copy").takeIf { it.isNotEmpty() }?.let(Uri::parse)
                ?: return DiagnosticResult(name, "待核验", "核验记录缺少副本位置，原件已保留")
            return DiagnosticResult(name, VERIFIED_COPY, entry.optString("detail"), copy)
        }
        val target = entry.optString("target").takeIf { it.isNotEmpty() }?.let(Uri::parse)
        if (SaveResumePolicy.needsReconcile(status, target != null)) {
            if (target == null || !runCatching { converter.discardInterruptedTarget(target) }.getOrDefault(false)) {
                return DiagnosticResult(name, "待核验", "上次写入尚无法安全续跑，原件已保留", target)
            }
            val source = entry.optString("source")
            val editor = store.edit().remove(operation)
            if (source.isNotBlank()) editor.remove(pendingKey(source))
            check(editor.commit()) { "无法清除已确认的中断写入记录" }
        }
        return null
    }

    fun completeBatch() {
        check(store.edit().remove("activeBatch").commit()) { "无法关闭已完成批次" }
    }

    fun recover(converter: DiagnosticImport): List<DiagnosticResult> {
        return recent().mapNotNull { key ->
            val entry = read(key) ?: return@mapNotNull null
            val name = entry.optString("name")
            if (entry.has("status") && entry.optString("status") != "待核验") {
                return@mapNotNull withSource(DiagnosticResult(name, entry.optString("status"),
                    entry.optString("detail"), entry.optString("copy").takeIf { it.isNotEmpty() }?.let(Uri::parse)), key, entry)
            }
            val target = entry.optString("target").takeIf { it.isNotEmpty() }?.let(Uri::parse)
            val recovered = target?.let {
                runCatching { converter.recover(name, it, entry.optString("hash")) }.getOrNull()
            }
            if (recovered != null) {
                val generation = if (recovered.status == VERIFIED_COPY) recovered.copy?.let {
                    runCatching { TrashCoordinator(appContext).captureCopyGeneration(it) }.getOrNull()
                } else null
                runCatching { finish(key, recovered, generation) }
            }
            withSource(recovered ?: DiagnosticResult(name, "待核验", "上次写入尚无法确认，原件已保留", target), key, entry)
        }
    }

    private fun withSource(result: DiagnosticResult, operation: String, entry: JSONObject): DiagnosticResult =
        result.copy(operation = operation,
            source = entry.optString("source").takeIf { it.isNotEmpty() }?.let(Uri::parse),
            captureTime = entry.optLong("captureTime").takeIf { it > 0 })

    fun trashEntries(): List<Pair<TrashCandidate, TrashStatus?>> = recent().mapNotNull { operation ->
        val entry = read(operation) ?: return@mapNotNull null
        if (!TrashPolicy.verified(entry.optString("status"), entry.has("copyGeneration"))) return@mapNotNull null
        val source = entry.optString("source").takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return@mapNotNull null
        val copy = entry.optString("copy").takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return@mapNotNull null
        val sourceHash = entry.optString("sourceHash").takeIf { it.length == 64 } ?: return@mapNotNull null
        val copyHash = entry.optString("hash").takeIf { it.length == 64 } ?: return@mapNotNull null
        val candidate = TrashCandidate(operation, entry.getString("name"), source, copy,
            entry.getString("providerVersion"), entry.getLong("generation"), entry.getLong("size"), sourceHash,
            entry.getLong("copyGeneration"), copyHash, entry.optString("copyName").ifBlank { null })
        candidate to entry.optString("trashState").takeIf { it.isNotEmpty() }?.let(TrashStatus::valueOf)
    }

    fun recordTrashCheck(candidate: TrashCandidate, status: TrashStatus) {
        val entry = read(candidate.operation) ?: error("缺少操作记录")
        val previousVersion = entry.getString("providerVersion")
        if (previousVersion != candidate.volumeVersion && status != TrashStatus.NEEDS_CHECK) {
            entry.put("source", candidate.source.toString()).put("copy", candidate.copy.toString())
                .put("providerVersion", candidate.volumeVersion).put("generation", candidate.sourceGeneration)
                .put("copyGeneration", candidate.copyGeneration).put("copyName", candidate.copyName)
        } else if (entry.optString("trashState") == status.name) {
            return
        }
        write(candidate.operation, entry.put("trashState", status.name).toString())
    }

    fun setTrashStatus(operations: List<String>, status: TrashStatus?) {
        if (operations.isEmpty()) return
        val editor = store.edit()
        for (operation in operations) {
            val entry = read(operation) ?: error("缺少操作记录")
            if (status == null) entry.remove("trashState") else entry.put("trashState", status.name)
            editor.putString(operation, entry.toString())
        }
        check(editor.commit()) { "无法保存回收站请求状态" }
    }

    private fun recent(): List<String> {
        val array = runCatching { JSONArray(store.getString("recent", "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).map(array::getString)
    }

    private fun pendingKey(source: String) = "pending:$source"
    private fun read(key: String) = store.getString(key, null)?.let(::JSONObject)
    private fun write(key: String, value: String) {
        check(store.edit().putString(key, value).commit()) { "无法保存批次记录" }
    }
}
